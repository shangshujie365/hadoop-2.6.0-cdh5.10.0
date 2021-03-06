<!---
  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License. See accompanying LICENSE file.
-->

# Hadoop-AWS module: Integration with Amazon Web Services

<!-- MACRO{toc|fromDepth=0|toDepth=5} -->

## Overview

The `hadoop-aws` module provides support for AWS integration. The generated
JAR file, `hadoop-aws.jar` also declares a transitive dependency on all
external artifacts which are needed for this support —enabling downstream
applications to easily use this support.

### Features

1. The "classic" `s3:` filesystem for storing objects in Amazon S3 Storage.
**NOTE: `s3:` is being phased out. Use `s3n:` or `s3a:` instead.**
1. The second-generation, `s3n:` filesystem, making it easy to share
data between hadoop and other applications via the S3 object store.
1. The third generation, `s3a:` filesystem. Designed to be a switch in
replacement for `s3n:`, this filesystem binding supports larger files and promises
higher performance.

The specifics of using these filesystems are documented below.

### Warning #1: Object Stores are not filesystems.

Amazon S3 is an example of "an object store". In order to achieve scalability
and especially high availability, S3 has —as many other cloud object stores have
done— relaxed some of the constraints which classic "POSIX" filesystems promise.

Specifically

1. Files that are newly created from the Hadoop Filesystem APIs may not be
immediately visible.
2. File delete and update operations may not immediately propagate. Old
copies of the file may exist for an indeterminate time period.
3. Directory operations: `delete()` and `rename()` are implemented by
recursive file-by-file operations. They take time at least proportional to
the number of files, during which time partial updates may be visible. If
the operations are interrupted, the filesystem is left in an intermediate state.

### Warning #2: Because Object stores don't track modification times of directories,
features of Hadoop relying on this can have unexpected behaviour. E.g. the
AggregatedLogDeletionService of YARN will not remove the appropriate logfiles.

For further discussion on these topics, please consult
[The Hadoop FileSystem API Definition](../../../hadoop-project-dist/hadoop-common/filesystem/index.html).

### Warning #3: your AWS credentials are valuable

Your AWS credentials not only pay for services, they offer read and write
access to the data. Anyone with the credentials can not only read your datasets
—they can delete them.

Do not inadvertently share these credentials through means such as
1. Checking in to SCM any configuration files containing the secrets.
1. Logging them to a console, as they invariably end up being seen.
1. Defining filesystem URIs with the credentials in the URL, such as
`s3a://AK0010:secret@landsat/`. They will end up in logs and error messages.
1. Including the secrets in bug reports.

If you do any of these: change your credentials immediately!

### Warning #4: the S3 client provided by Amazon EMR are not from the Apache
Software foundation, and are only supported by Amazon.

Specifically: on Amazon EMR, s3a is not supported, and amazon recommend
a different filesystem implementation. If you are using Amazon EMR, follow
these instructions —and be aware that all issues related to S3 integration
in EMR can only be addressed by Amazon themselves: please raise your issues
with them.

## S3

The `s3://` filesystem is the original S3 store in the Hadoop codebase.
It implements an inode-style filesystem atop S3, and was written to
provide scaleability when S3 had significant limits on the size of blobs.
It is incompatible with any other application's use of data in S3.

It is now deprecated and will be removed in Hadoop 3. Please do not use,
and migrate off data which is on it.

### Dependencies

* `jets3t` jar
* `commons-codec` jar
* `commons-logging` jar
* `httpclient` jar
* `httpcore` jar
* `java-xmlbuilder` jar

### Authentication properties

    <property>
      <name>fs.s3.awsAccessKeyId</name>
      <description>AWS access key ID</description>
    </property>

    <property>
      <name>fs.s3.awsSecretAccessKey</name>
      <description>AWS secret key</description>
    </property>


## S3N

S3N was the first S3 Filesystem client which used "native" S3 objects, hence
the schema `s3n://`.

### Features

* Directly reads and writes S3 objects.
* Compatible with standard S3 clients.
* Supports partitioned uploads for many-GB objects.
* Available across all Hadoop 2.x releases.

The S3N filesystem client, while widely used, is no longer undergoing
active maintenance except for emergency security issues. There are
known bugs, especially: it reads to end of a stream when closing a read;
this can make `seek()` slow on large files. The reason there has been no
attempt to fix this is that every upgrade of the Jets3t library, while
fixing some problems, has unintentionally introduced new ones in either the changed
Hadoop code, or somewhere in the Jets3t/Httpclient code base.
The number of defects remained constant, they merely moved around.

By freezing the Jets3t jar version and avoiding changes to the code,
we reduce the risk of making things worse.

The S3A filesystem client can read all files created by S3N. Accordingly
it should be used wherever possible.


### Dependencies

* `jets3t` jar
* `commons-codec` jar
* `commons-logging` jar
* `httpclient` jar
* `httpcore` jar
* `java-xmlbuilder` jar


### Authentication properties

    <property>
      <name>fs.s3n.awsAccessKeyId</name>
      <description>AWS access key ID</description>
    </property>

    <property>
      <name>fs.s3n.awsSecretAccessKey</name>
      <description>AWS secret key</description>
    </property>

### Other properties

    <property>
      <name>fs.s3.buffer.dir</name>
      <value>${hadoop.tmp.dir}/s3</value>
      <description>Determines where on the local filesystem the s3:/s3n: filesystem
      should store files before sending them to S3
      (or after retrieving them from S3).
      </description>
    </property>

    <property>
      <name>fs.s3.maxRetries</name>
      <value>4</value>
      <description>The maximum number of retries for reading or writing files to
        S3, before we signal failure to the application.
      </description>
    </property>

    <property>
      <name>fs.s3.sleepTimeSeconds</name>
      <value>10</value>
      <description>The number of seconds to sleep between each S3 retry.
      </description>
    </property>

    <property>
      <name>fs.s3n.block.size</name>
      <value>67108864</value>
      <description>Block size to use when reading files using the native S3
      filesystem (s3n: URIs).</description>
    </property>

    <property>
      <name>fs.s3n.multipart.uploads.enabled</name>
      <value>false</value>
      <description>Setting this property to true enables multiple uploads to
      native S3 filesystem. When uploading a file, it is split into blocks
      if the size is larger than fs.s3n.multipart.uploads.block.size.
      </description>
    </property>

    <property>
      <name>fs.s3n.multipart.uploads.block.size</name>
      <value>67108864</value>
      <description>The block size for multipart uploads to native S3 filesystem.
      Default size is 64MB.
      </description>
    </property>

    <property>
      <name>fs.s3n.multipart.copy.block.size</name>
      <value>5368709120</value>
      <description>The block size for multipart copy in native S3 filesystem.
      Default size is 5GB.
      </description>
    </property>

    <property>
      <name>fs.s3n.server-side-encryption-algorithm</name>
      <value></value>
      <description>Specify a server-side encryption algorithm for S3.
      Unset by default, and the only other currently allowable value is AES256.
      </description>
    </property>

## S3A


The S3A filesystem client, prefix `s3a://`, is the S3 client undergoing
active development and maintenance.
While this means that there is a bit of instability
of configuration options and behavior, it also means
that the code is getting better in terms of reliability, performance,
monitoring and other features.

### Features

* Directly reads and writes S3 objects.
* Compatible with standard S3 clients.
* Can read data created with S3N.
* Can write data back that is readable by S3N. (Note: excluding encryption).
* Supports partitioned uploads for many-GB objects.
* Instrumented with Hadoop metrics.
* Performance optimized operations, including `seek()` and `readFully()`.
* Uses Amazon's Java S3 SDK with support for latest S3 features and authentication
schemes.
* Supports authentication via: environment variables, Hadoop configuration
properties, the Hadoop key management store and IAM roles.
* Supports S3 "Server Side Encryption" for both reading and writing.
* Supports proxies
* Test suites includes distcp and suites in downstream projects.
* Available since Hadoop 2.6; considered production ready in Hadoop 2.7.
* Actively maintained.

S3A is now the recommended client for working with S3 objects. It is also the
one where patches for functionality and performance are very welcome.

### Dependencies

* `hadoop-aws` jar.
* `aws-java-sdk-s3` jar.
* `aws-java-sdk-core` jar.
* `aws-java-sdk-kms` jar.
* `joda-time` jar; use version 2.8.1 or later.
* `httpclient` jar.
* Jackson `jackson-core`, `jackson-annotations`, `jackson-databind` jars.

### S3A Authentication methods

S3A supports multiple authentication mechanisms, and can be configured as to
which mechanisms to use, and the order to use them. Custom implementations
of `com.amazonaws.auth.AWSCredentialsProvider` may also be used.

### Authentication properties

    <property>
      <name>fs.s3a.access.key</name>
      <description>AWS access key ID.
       Omit for IAM role-based or provider-based authentication.</description>
    </property>

    <property>
      <name>fs.s3a.secret.key</name>
      <description>AWS secret key.
       Omit for IAM role-based or provider-based authentication.</description>
    </property>

    <property>
      <name>fs.s3a.aws.credentials.provider</name>
      <description>
        Comma-separated class names of credential provider classes which implement
        com.amazonaws.auth.AWSCredentialsProvider.

        These are loaded and queried in sequence for a valid set of credentials.
        Each listed class must provide either an accessible constructor accepting
        java.net.URI and org.apache.hadoop.conf.Configuration, or an accessible
        default constructor.

        Specifying org.apache.hadoop.fs.s3a.AnonymousAWSCredentialsProvider allows
        anonymous access to a publicly accessible S3 bucket without any credentials.
        Please note that allowing anonymous access to an S3 bucket compromises
        security and therefore is unsuitable for most use cases. It can be useful
        for accessing public data sets without requiring AWS credentials.
      </description>
    </property>

    <property>
      <name>fs.s3a.session.token</name>
      <description>
        Session token, when using org.apache.hadoop.fs.s3a.TemporaryAWSCredentialsProvider
        as one of the providers.
      </description>
    </property>


#### Authenticating via environment variables

S3A supports configuration via [the standard AWS environment variables](http://docs.aws.amazon.com/cli/latest/userguide/cli-chap-getting-started.html#cli-environment).

The core environment variables are for the access key and associated secret:

```
export AWS_ACCESS_KEY_ID=my.aws.key
export AWS_SECRET_ACCESS_KEY=my.secret.key
```

If the environment variable `AWS_SESSION_TOKEN` is set, session authentication
using "Temporary Security Credentials" is enabled; the Key ID and secret key
must be set to the credentials for that specific sesssion.

```
export AWS_SESSION_TOKEN=SECRET-SESSION-TOKEN
export AWS_ACCESS_KEY_ID=SESSION-ACCESS-KEY
export AWS_SECRET_ACCESS_KEY=SESSION-SECRET-KEY
```

These environment variables can be used to set the authentication credentials
instead of properties in the Hadoop configuration.

*Important:*
These environment variables are not propagated from client to server when
YARN applications are launched. That is: having the AWS environment variables
set when an application is launched will not permit the launched application
to access S3 resources. The environment variables must (somehow) be set
on the hosts/processes where the work is executed.


#### Changing Authentication Providers

The standard way to authenticate is with an access key and secret key using the
properties in the configuration file.

The S3A client follows the following authentication chain:

1. If login details were provided in the filesystem URI, a warning is printed
and then the username and password extracted for the AWS key and secret respectively.
1. The `fs.s3a.access.key` and `fs.s3a.secret.key` are looked for in the Hadoop
XML configuration.
1. The [AWS environment variables](http://docs.aws.amazon.com/cli/latest/userguide/cli-chap-getting-started.html#cli-environment),
are then looked for.
1. An attempt is made to query the Amazon EC2 Instance Metadata Service to
 retrieve credentials published to EC2 VMs.

S3A can be configured to obtain client authentication providers from classes
which integrate with the AWS SDK by implementing the `com.amazonaws.auth.AWSCredentialsProvider`
Interface. This is done by listing the implementation classes, in order of
preference, in the configuration option `fs.s3a.aws.credentials.provider`.

*Important*: AWS Credential Providers are distinct from _Hadoop Credential Providers_.
As will be covered later, Hadoop Credential Providers allow passwords and other secrets
to be stored and transferred more securely than in XML configuration files.
AWS Credential Providers are classes which can be used by the Amazon AWS SDK to
obtain an AWS login from a different source in the system, including environment
variables, JVM properties and configuration files.

There are three AWS Credential Providers inside the `hadoop-aws` JAR:

| classname | description |
|-----------|-------------|
| `org.apache.hadoop.fs.s3a.TemporaryAWSCredentialsProvider`| Session Credentials |
| `org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider`| Simple name/secret credentials |
| `org.apache.hadoop.fs.s3a.AnonymousAWSCredentialsProvider`| Anonymous Login |

There are also many in the Amazon SDKs, in particular two which are automatically
set up in the authentication chain:

| classname | description |
|-----------|-------------|
| `com.amazonaws.auth.InstanceProfileCredentialsProvider`| EC2 Metadata Credentials |
| `com.amazonaws.auth.EnvironmentVariableCredentialsProvider`| AWS Environment Variables |


*Session Credentials with `TemporaryAWSCredentialsProvider`*

[Temporary Security Credentials](http://docs.aws.amazon.com/IAM/latest/UserGuide/id_credentials_temp.html)
can be obtained from the Amazon Security Token Service; these
consist of an access key, a secret key, and a session token.

To authenticate with these:

1. Declare `org.apache.hadoop.fs.s3a.TemporaryAWSCredentialsProvider` as the
provider.
1. Set the session key in the property `fs.s3a.session.token`,
and the access and secret key properties to those of this temporary session.

Example:

```xml
<property>
  <name>fs.s3a.aws.credentials.provider</name>
  <value>org.apache.hadoop.fs.s3a.TemporaryAWSCredentialsProvider</value>
</property>

<property>
  <name>fs.s3a.access.key</name>
  <value>SESSION-ACCESS-KEY</value>
</property>

<property>
  <name>fs.s3a.secret.key</name>
  <value>SESSION-SECRET-KEY</value>
</property>

<property>
  <name>fs.s3a.session.token</name>
  <value>SECRET-SESSION-TOKEN</value>
</property>
```

The lifetime of session credentials are fixed when the credentials
are issued; once they expire the application will no longer be able to
authenticate to AWS.

*Anonymous Login with `AnonymousAWSCredentialsProvider`*

Specifying `org.apache.hadoop.fs.s3a.AnonymousAWSCredentialsProvider` allows
anonymous access to a publicly accessible S3 bucket without any credentials.
It can be useful for accessing public data sets without requiring AWS credentials.

```xml
<property>
  <name>fs.s3a.aws.credentials.provider</name>
  <value>org.apache.hadoop.fs.s3a.AnonymousAWSCredentialsProvider</value>
</property>
```

Once this is done, there's no need to supply any credentials
in the Hadoop configuration or via environment variables.

This option can be used to verify that an object store does
not permit unauthenticated access: that is, if an attempt to list
a bucket is made using the anonymous credentials, it should fail —unless
explicitly opened up for broader access.

```bash
hadoop fs -ls \
 -D fs.s3a.aws.credentials.provider=org.apache.hadoop.fs.s3a.AnonymousAWSCredentialsProvider \
 s3a://landsat-pds/
```

1. Allowing anonymous access to an S3 bucket compromises
security and therefore is unsuitable for most use cases.

1. If a list of credential providers is given in `fs.s3a.aws.credentials.provider`,
then the Anonymous Credential provider *must* come last. If not, credential
providers listed after it will be ignored.

*Simple name/secret credentials with `SimpleAWSCredentialsProvider`*

This is is the standard credential provider, which
supports the secret key in `fs.s3a.access.key` and token in `fs.s3a.secret.key`
values. It does not support authentication with logins credentials declared
in the URLs.

    <property>
      <name>fs.s3a.aws.credentials.provider</name>
      <value>org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider</value>
    </property>

Apart from its lack of support of user:password details being included in filesystem
URLs (a dangerous practise that is strongly discouraged), this provider acts
exactly at the basic authenticator used in the default authentication chain.

This means that the default S3A authentication chain can be defined as

    <property>
      <name>fs.s3a.aws.credentials.provider</name>
      <value>
      org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider,
      com.amazonaws.auth.EnvironmentVariableCredentialsProvider,
      com.amazonaws.auth.InstanceProfileCredentialsProvider
      </value>
    </property>


#### Protecting the AWS Credentials

To protect the access/secret keys from prying eyes, it is recommended that you
use either IAM role-based authentication (such as EC2 instance profile) or
the credential provider framework securely storing them and accessing them
through configuration. The following describes using the latter for AWS
credentials in the S3A FileSystem.


##### Storing secrets with Hadoop Credential Providers

The Hadoop Credential Provider Framework allows secure "Credential Providers"
to keep secrets outside Hadoop configuration files, storing them in encrypted
files in local or Hadoop filesystems, and including them in requests.

The S3A configuration options with sensitive data
(`fs.s3a.secret.key`, `fs.s3a.access.key` and `fs.s3a.session.token`) can
have their data saved to a binary file stored, with the values being read in
when the S3A filesystem URL is used for data access. The reference to this
credential provider is all that is passed as a direct configuration option.

For additional reading on the Hadoop Credential Provider API see:
[Credential Provider API](../../../hadoop-project-dist/hadoop-common/CredentialProviderAPI.html).


###### Create a credential file

A credential file can be created on any Hadoop filesystem; when creating one on HDFS or
a Unix filesystem the permissions are automatically set to keep the file
private to the reader —though as directory permissions are not touched,
users should verify that the directory containing the file is readable only by
the current user.


```bash
hadoop credential create fs.s3a.access.key -value 123 \
    -provider jceks://hdfs@nn1.example.com:9001/user/backup/s3.jceks

hadoop credential create fs.s3a.secret.key -value 456 \
    -provider jceks://hdfs@nn1.example.com:9001/user/backup/s3.jceks
```

A credential file can be listed, to see what entries are kept inside it

```bash
hadoop credential list -provider jceks://hdfs@nn1.example.com:9001/user/backup/s3.jceks

Listing aliases for CredentialProvider: jceks://hdfs@nn1.example.com:9001/user/backup/s3.jceks
fs.s3a.secret.key
fs.s3a.access.key
```
At this point, the credentials are ready for use.

###### Configure the `hadoop.security.credential.provider.path` property

The URL to the provider must be set in the configuration property
`hadoop.security.credential.provider.path`, either on the command line or
in XML configuration files.

```xml
<property>
  <name>hadoop.security.credential.provider.path</name>
  <value>jceks://hdfs@nn1.example.com:9001/user/backup/s3.jceks</value>
  <description>Path to interrogate for protected credentials.</description>
</property>
```

Because this property only supplies the path to the secrets file, the configuration
option itself is no longer a sensitive item.

###### Using the credentials

Once the provider is set in the Hadoop configuration, hadoop commands
work exactly as if the secrets were in an XML file.

```bash

hadoop distcp \
    hdfs://nn1.example.com:9001/user/backup/007020615 s3a://glacier1/

hadoop fs -ls s3a://glacier1/

```

The path to the provider can also be set on the command line:

```bash
hadoop distcp \
    -D hadoop.security.credential.provider.path=jceks://hdfs@nn1.example.com:9001/user/backup/s3.jceks \
    hdfs://nn1.example.com:9001/user/backup/007020615 s3a://glacier1/

hadoop fs \
  -D hadoop.security.credential.provider.path=jceks://hdfs@nn1.example.com:9001/user/backup/s3.jceks \
  -ls s3a://glacier1/

```

Because the provider path is not itself a sensitive secret, there is no risk
from placing its declaration on the command line.


### Other properties

    <property>
      <name>fs.s3a.connection.maximum</name>
      <value>15</value>
      <description>Controls the maximum number of simultaneous connections to S3.</description>
    </property>

    <property>
      <name>fs.s3a.connection.ssl.enabled</name>
      <value>true</value>
      <description>Enables or disables SSL connections to S3.</description>
    </property>

    <property>
      <name>fs.s3a.endpoint</name>
      <description>AWS S3 endpoint to connect to. An up-to-date list is
        provided in the AWS Documentation: regions and endpoints. Without this
        property, the standard region (s3.amazonaws.com) is assumed.
      </description>
    </property>

    <property>
      <name>fs.s3a.path.style.access</name>
      <value>false</value>
      <description>Enable S3 path style access ie disabling the default virtual hosting behaviour.
        Useful for S3A-compliant storage providers as it removes the need to set up DNS for virtual hosting.
      </description>
    </property>

    <property>
      <name>fs.s3a.proxy.host</name>
      <description>Hostname of the (optional) proxy server for S3 connections.</description>
    </property>

    <property>
      <name>fs.s3a.proxy.port</name>
      <description>Proxy server port. If this property is not set
        but fs.s3a.proxy.host is, port 80 or 443 is assumed (consistent with
        the value of fs.s3a.connection.ssl.enabled).</description>
    </property>

    <property>
      <name>fs.s3a.proxy.username</name>
      <description>Username for authenticating with proxy server.</description>
    </property>

    <property>
      <name>fs.s3a.proxy.password</name>
      <description>Password for authenticating with proxy server.</description>
    </property>

    <property>
      <name>fs.s3a.proxy.domain</name>
      <description>Domain for authenticating with proxy server.</description>
    </property>

    <property>
      <name>fs.s3a.proxy.workstation</name>
      <description>Workstation for authenticating with proxy server.</description>
    </property>

    <property>
      <name>fs.s3a.attempts.maximum</name>
      <value>10</value>
      <description>How many times we should retry commands on transient errors.</description>
    </property>

    <property>
      <name>fs.s3a.connection.establish.timeout</name>
      <value>5000</value>
      <description>Socket connection setup timeout in milliseconds.</description>
    </property>

    <property>
      <name>fs.s3a.connection.timeout</name>
      <value>200000</value>
      <description>Socket connection timeout in milliseconds.</description>
    </property>

    <property>
      <name>fs.s3a.paging.maximum</name>
      <value>5000</value>
      <description>How many keys to request from S3 when doing
         directory listings at a time.</description>
    </property>

    <property>
      <name>fs.s3a.threads.max</name>
      <value>256</value>
      <description> Maximum number of concurrent active (part)uploads,
      which each use a thread from the threadpool.</description>
    </property>

    <property>
      <name>fs.s3a.socket.send.buffer</name>
      <value>8192</value>
      <description>Socket send buffer hint to amazon connector. Represented in bytes.</description>
    </property>

    <property>
      <name>fs.s3a.socket.recv.buffer</name>
      <value>8192</value>
      <description>Socket receive buffer hint to amazon connector. Represented in bytes.</description>
    </property>

    <property>
      <name>fs.s3a.threads.core</name>
      <value>15</value>
      <description>Number of core threads in the threadpool.</description>
    </property>

    <property>
      <name>fs.s3a.threads.keepalivetime</name>
      <value>60</value>
      <description>Number of seconds a thread can be idle before being
        terminated.</description>
    </property>

    <property>
      <name>fs.s3a.max.total.tasks</name>
      <value>1000</value>
      <description>Number of (part)uploads allowed to the queue before
      blocking additional uploads.</description>
    </property>

    <property>
      <name>fs.s3a.multipart.size</name>
      <value>67108864</value>
      <description>How big (in bytes) to split upload or copy operations up into.
      This also controls the partition size in renamed files, as rename() involves
      copying the source file(s)</description>
    </property>

    <property>
      <name>fs.s3a.multipart.threshold</name>
      <value>134217728</value>
      <description>Threshold before uploads or copies use parallel multipart operations.</description>
    </property>

    <property>
      <name>fs.s3a.multiobjectdelete.enable</name>
      <value>true</value>
      <description>When enabled, multiple single-object delete requests are replaced by
        a single 'delete multiple objects'-request, reducing the number of requests.
        Beware: legacy S3-compatible object stores might not support this request.
      </description>
    </property>

    <property>
      <name>fs.s3a.acl.default</name>
      <description>Set a canned ACL for newly created and copied objects. Value may be Private,
        PublicRead, PublicReadWrite, AuthenticatedRead, LogDeliveryWrite, BucketOwnerRead,
        or BucketOwnerFullControl.</description>
    </property>

    <property>
      <name>fs.s3a.multipart.purge</name>
      <value>false</value>
      <description>True if you want to purge existing multipart uploads that may not have been
         completed/aborted correctly</description>
    </property>

    <property>
      <name>fs.s3a.multipart.purge.age</name>
      <value>86400</value>
      <description>Minimum age in seconds of multipart uploads to purge</description>
    </property>

    <property>
      <name>fs.s3a.signing-algorithm</name>
      <description>Override the default signing algorithm so legacy
        implementations can still be used</description>
    </property>

    <property>
      <name>fs.s3a.server-side-encryption-algorithm</name>
      <description>Specify a server-side encryption algorithm for s3a: file system.
        Unset by default, and the only other currently allowable value is AES256.
      </description>
    </property>

    <property>
      <name>fs.s3a.buffer.dir</name>
      <value>${hadoop.tmp.dir}/s3a</value>
      <description>Comma separated list of directories that will be used to buffer file
        uploads to. No effect if fs.s3a.fast.upload is true.</description>
    </property>

    <property>
      <name>fs.s3a.block.size</name>
      <value>33554432</value>
      <description>Block size to use when reading files using s3a: file system.
      </description>
    </property>

    <property>
      <name>fs.s3a.user.agent.prefix</name>
      <value></value>
      <description>
        Sets a custom value that will be prepended to the User-Agent header sent in
        HTTP requests to the S3 back-end by S3AFileSystem.  The User-Agent header
        always includes the Hadoop version number followed by a string generated by
        the AWS SDK.  An example is "User-Agent: Hadoop 2.8.0, aws-sdk-java/1.10.6".
        If this optional property is set, then its value is prepended to create a
        customized User-Agent.  For example, if this configuration property was set
        to "MyApp", then an example of the resulting User-Agent would be
        "User-Agent: MyApp, Hadoop 2.8.0, aws-sdk-java/1.10.6".
      </description>
    </property>

    <property>
      <name>fs.s3a.impl</name>
      <value>org.apache.hadoop.fs.s3a.S3AFileSystem</value>
      <description>The implementation class of the S3A Filesystem</description>
    </property>

    <property>
      <name>fs.AbstractFileSystem.s3a.impl</name>
      <value>org.apache.hadoop.fs.s3a.S3A</value>
      <description>The implementation class of the S3A AbstractFileSystem.</description>
    </property>

    <property>
      <name>fs.s3a.readahead.range</name>
      <value>65536</value>
      <description>Bytes to read ahead during a seek() before closing and
      re-opening the S3 HTTP connection. This option will be overridden if
      any call to setReadahead() is made to an open stream.</description>
    </property>

### Working with buckets in different regions

S3 Buckets are hosted in different regions, the default being US-East.
The client talks to it by default, under the URL `s3.amazonaws.com`

S3A can work with buckets from any region. Each region has its own
S3 endpoint, documented [by Amazon](http://docs.aws.amazon.com/general/latest/gr/rande.html#s3_region).

1. Applications running in EC2 infrastructure do not pay for IO to/from
*local S3 buckets*. They will be billed for access to remote buckets. Always
use local buckets and local copies of data, wherever possible.
1. The default S3 endpoint can support data IO with any bucket when the V1 request
signing protocol is used.
1. When the V4 signing protocol is used, AWS requires the explicit region endpoint
to be used —hence S3A must be configured to use the specific endpoint. This
is done in the configuration option `fs.s3a.endpoint`.
1. All endpoints other than the default endpoint only support interaction
with buckets local to that S3 instance.

While it is generally simpler to use the default endpoint, working with
V4-signing-only regions (Frankfurt, Seoul) requires the endpoint to be identified.
Expect better performance from direct connections —traceroute will give you some insight.

Examples:

The default endpoint:

```xml
<property>
  <name>fs.s3a.endpoint</name>
  <value>s3.amazonaws.com</value>
</property>
```

Frankfurt

```xml
<property>
  <name>fs.s3a.endpoint</name>
  <value>s3.eu-central-1.amazonaws.com</value>
</property>
```

Seoul
```xml
<property>
  <name>fs.s3a.endpoint</name>
  <value>s3.ap-northeast-2.amazonaws.com</value>
</property>
```

If the wrong endpoint is used, the request may fail. This may be reported as a 301/redirect error,
or as a 400 Bad Request.

### S3AFastOutputStream
 **Warning: NEW in hadoop 2.7. UNSTABLE, EXPERIMENTAL: use at own risk**

    <property>
      <name>fs.s3a.fast.upload</name>
      <value>false</value>
      <description>Upload directly from memory instead of buffering to
      disk first. Memory usage and parallelism can be controlled as up to
      fs.s3a.multipart.size memory is consumed for each (part)upload actively
      uploading (fs.s3a.threads.max) or queueing (fs.s3a.max.total.tasks)</description>
    </property>

    <property>
      <name>fs.s3a.fast.buffer.size</name>
      <value>1048576</value>
      <description>Size (in bytes) of initial memory buffer allocated for an
      upload. No effect if fs.s3a.fast.upload is false.</description>
    </property>

Writes are buffered in memory instead of to a file on local disk. This
removes the throughput bottleneck of the local disk write and read cycle
before starting the actual upload. Furthermore, it allows handling files that
are larger than the remaining local disk space.

However, non-trivial memory tuning is needed for optimal results and careless
settings could cause memory overflow. Up to `fs.s3a.threads.max` parallel
(part)uploads are active. Furthermore, up to `fs.s3a.max.total.tasks`
additional part(uploads) can be waiting (and thus memory buffers are created).
The memory buffer is uploaded as a single upload if it is not larger than
`fs.s3a.multipart.threshold`. Else, a multi-part upload is initiated and
parts of size `fs.s3a.multipart.size` are used to protect against overflowing
the available memory. These settings should be tuned to the envisioned
workflow (some large files, many small ones, ...) and the physical
limitations of the machine and cluster (memory, network bandwidth).

### S3A Experimental "fadvise" input policy support

**Warning: EXPERIMENTAL: behavior may change in future**

The S3A Filesystem client supports the notion of input policies, similar
to that of the Posix `fadvise()` API call. This tunes the behavior of the S3A
client to optimise HTTP GET requests for the different use cases.

#### "sequential" (default)

Read through the file, possibly with some short forward seeks.

The whole document is requested in a single HTTP request; forward seeks
within the readahead range are supported by skipping over the intermediate
data.

This is leads to maximum read throughput —but with very expensive
backward seeks.


#### "normal"

This is currently the same as "sequential".

#### "random"

Optimised for random IO, specifically the Hadoop `PositionedReadable`
operations —though `seek(offset); read(byte_buffer)` also benefits.

Rather than ask for the whole file, the range of the HTTP request is
set to that that of the length of data desired in the `read` operation
(Rounded up to the readahead value set in `setReadahead()` if necessary).

By reducing the cost of closing existing HTTP requests, this is
highly efficient for file IO accessing a binary file
through a series of `PositionedReadable.read()` and `PositionedReadable.readFully()`
calls. Sequential reading of a file is expensive, as now many HTTP requests must
be made to read through the file.

For operations simply reading through a file: copying, distCp, reading
Gzipped or other compressed formats, parsing .csv files, etc, the `sequential`
policy is appropriate. This is the default: S3A does not need to be configured.

For the specific case of high-performance random access IO, the `random` policy
may be considered. The requirements are:

* Data is read using the `PositionedReadable` API.
* Long distance (many MB) forward seeks
* Backward seeks as likely as forward seeks.
* Little or no use of single character `read()` calls or small `read(buffer)`
calls.
* Applications running close to the S3 data store. That is: in EC2 VMs in
the same datacenter as the S3 instance.

The desired fadvise policy must be set in the configuration option
`fs.s3a.experimental.input.fadvise` when the filesystem instance is created.
That is: it can only be set on a per-filesystem basis, not on a per-file-read
basis.

    <property>
      <name>fs.s3a.experimental.input.fadvise</name>
      <value>random</value>
      <description>Policy for reading files.
       Values: 'random', 'sequential' or 'normal'
       </description>
    </property>

[HDFS-2744](https://issues.apache.org/jira/browse/HDFS-2744),
*Extend FSDataInputStream to allow fadvise* proposes adding a public API
to set fadvise policies on input streams. Once implemented,
this will become the supported mechanism used for configuring the input IO policy.

## Troubleshooting S3A

Common problems working with S3A are

1. Classpath
1. Authentication
1. S3 Inconsistency side-effects

Classpath is usually the first problem. For the S3x filesystem clients,
you need the Hadoop-specific filesystem clients, third party S3 client libraries
compatible with the Hadoop code, and any dependent libraries compatible with
Hadoop and the specific JVM.

The classpath must be set up for the process talking to S3: if this is code
running in the Hadoop cluster, the JARs must be on that classpath. That
includes `distcp`.


### `ClassNotFoundException: org.apache.hadoop.fs.s3a.S3AFileSystem`

(or `org.apache.hadoop.fs.s3native.NativeS3FileSystem`, `org.apache.hadoop.fs.s3.S3FileSystem`).

These are the Hadoop classes, found in the `hadoop-aws` JAR. An exception
reporting one of these classes is missing means that this JAR is not on
the classpath.

### `ClassNotFoundException: com.amazonaws.services.s3.AmazonS3Client`

(or other `com.amazonaws` class.)

This means that one or more of the `aws-*-sdk` JARs are missing. Add them.

### Missing method in AWS class

This can be triggered by incompatibilities between the AWS SDK on the classpath
and the version which Hadoop was compiled with.

The AWS SDK JARs change their signature enough between releases that the only
way to safely update the AWS SDK version is to recompile Hadoop against the later
version.

There's nothing the Hadoop team can do here: if you get this problem, then sorry,
but you are on your own. The Hadoop developer team did look at using reflection
to bind to the SDK, but there were too many changes between versions for this
to work reliably. All it did was postpone version compatibility problems until
the specific codepaths were executed at runtime —this was actually a backward
step in terms of fast detection of compatibility problems.

### Missing method in a Jackson class

This is usually caused by version mismatches between Jackson JARs on the
classpath. All Jackson JARs on the classpath *must* be of the same version.


### Authentication failure

The general cause is: you have the wrong credentials —or somehow
the credentials were not readable on the host attempting to read or write
the S3 Bucket.

There's not much that Hadoop can do for diagnostics here.
Enabling debug logging for the package `org.apache.hadoop.fs.s3a`
can help somewhat.

Most common: there's an error in the key or secret.

Otherwise, try to use the AWS command line tools with the same credentials.
If you set the environment variables, you can take advantage of S3A's support
of environment-variable authentication by attempting to use the `hdfs fs` command
to read or write data on S3. That is: comment out the `fs.s3a` secrets and rely on
the environment variables.

### Authentication failure when using URLs with embedded secrets

If using the (strongly discouraged) mechanism of including the
AWS Key and secret in a URL, then both "+" and "/" symbols need
to encoded in the URL. As many AWS secrets include these characters,
encoding problems are not uncommon.

| symbol | encoded  value|
|-----------|-------------|
| `+` | `%2B` |
| `/` | `%2F` |


That is, a URL for `bucket` with AWS ID `user1` and secret `a+b/c` would
be represented as

```
s3a://user1:a%2Bb%2Fc@bucket
```

This technique is only needed when placing secrets in the URL. Again,
this is something users are strongly advised against using.

### Authentication failures running on Java 8u60+

A change in the Java 8 JVM broke some of the `toString()` string generation
of Joda Time 2.8.0, which stopped the Amazon S3 client from being able to
generate authentication headers suitable for validation by S3.

Fix: make sure that the version of Joda Time is 2.8.1 or later.

### "Bad Request" exception when working with AWS S3 Frankfurt, Seoul, or other "V4" endpoint


S3 Frankfurt and Seoul *only* support
[the V4 authentication API](http://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-authenticating-requests.html).

Requests using the V2 API will be rejected with 400 `Bad Request`

```
$ bin/hadoop fs -ls s3a://frankfurt/
WARN s3a.S3AFileSystem: Client: Amazon S3 error 400: 400 Bad Request; Bad Request (retryable)

com.amazonaws.services.s3.model.AmazonS3Exception: Bad Request (Service: Amazon S3; Status Code: 400; Error Code: 400 Bad Request; Request ID: 923C5D9E75E44C06), S3 Extended Request ID: HDwje6k+ANEeDsM6aJ8+D5gUmNAMguOk2BvZ8PH3g9z0gpH+IuwT7N19oQOnIr5CIx7Vqb/uThE=
	at com.amazonaws.http.AmazonHttpClient.handleErrorResponse(AmazonHttpClient.java:1182)
	at com.amazonaws.http.AmazonHttpClient.executeOneRequest(AmazonHttpClient.java:770)
	at com.amazonaws.http.AmazonHttpClient.executeHelper(AmazonHttpClient.java:489)
	at com.amazonaws.http.AmazonHttpClient.execute(AmazonHttpClient.java:310)
	at com.amazonaws.services.s3.AmazonS3Client.invoke(AmazonS3Client.java:3785)
	at com.amazonaws.services.s3.AmazonS3Client.headBucket(AmazonS3Client.java:1107)
	at com.amazonaws.services.s3.AmazonS3Client.doesBucketExist(AmazonS3Client.java:1070)
	at org.apache.hadoop.fs.s3a.S3AFileSystem.verifyBucketExists(S3AFileSystem.java:307)
	at org.apache.hadoop.fs.s3a.S3AFileSystem.initialize(S3AFileSystem.java:284)
	at org.apache.hadoop.fs.FileSystem.createFileSystem(FileSystem.java:2793)
	at org.apache.hadoop.fs.FileSystem.access$200(FileSystem.java:101)
	at org.apache.hadoop.fs.FileSystem$Cache.getInternal(FileSystem.java:2830)
	at org.apache.hadoop.fs.FileSystem$Cache.get(FileSystem.java:2812)
	at org.apache.hadoop.fs.FileSystem.get(FileSystem.java:389)
	at org.apache.hadoop.fs.Path.getFileSystem(Path.java:356)
	at org.apache.hadoop.fs.shell.PathData.expandAsGlob(PathData.java:325)
	at org.apache.hadoop.fs.shell.Command.expandArgument(Command.java:235)
	at org.apache.hadoop.fs.shell.Command.expandArguments(Command.java:218)
	at org.apache.hadoop.fs.shell.FsCommand.processRawArguments(FsCommand.java:103)
	at org.apache.hadoop.fs.shell.Command.run(Command.java:165)
	at org.apache.hadoop.fs.FsShell.run(FsShell.java:315)
	at org.apache.hadoop.util.ToolRunner.run(ToolRunner.java:76)
	at org.apache.hadoop.util.ToolRunner.run(ToolRunner.java:90)
	at org.apache.hadoop.fs.FsShell.main(FsShell.java:373)
ls: doesBucketExist on frankfurt-new: com.amazonaws.services.s3.model.AmazonS3Exception:
  Bad Request (Service: Amazon S3; Status Code: 400; Error Code: 400 Bad Request;
```

This happens when trying to work with any S3 service which only supports the
"V4" signing API —but the client is configured to use the default S3A service
endpoint.

The S3A client needs to be given the endpoint to use via the `fs.s3a.endpoint`
property.

As an example, the endpoint for S3 Frankfurt is `s3.eu-central-1.amazonaws.com`:

```xml
<property>
  <name>fs.s3a.endpoint</name>
  <value>s3.eu-central-1.amazonaws.com</value>
</property>
```

### Error message "The bucket you are attempting to access must be addressed using the specified endpoint"

This surfaces when `fs.s3a.endpoint` is configured to use S3 service endpoint
which is neither the original AWS one, `s3.amazonaws.com` , nor the one where
the bucket is hosted.

```
org.apache.hadoop.fs.s3a.AWSS3IOException: purging multipart uploads on landsat-pds:
 com.amazonaws.services.s3.model.AmazonS3Exception:
  The bucket you are attempting to access must be addressed using the specified endpoint.
  Please send all future requests to this endpoint.
   (Service: Amazon S3; Status Code: 301; Error Code: PermanentRedirect; Request ID: 5B7A5D18BE596E4B),
    S3 Extended Request ID: uE4pbbmpxi8Nh7rycS6GfIEi9UH/SWmJfGtM9IeKvRyBPZp/hN7DbPyz272eynz3PEMM2azlhjE=:

	at com.amazonaws.http.AmazonHttpClient.handleErrorResponse(AmazonHttpClient.java:1182)
	at com.amazonaws.http.AmazonHttpClient.executeOneRequest(AmazonHttpClient.java:770)
	at com.amazonaws.http.AmazonHttpClient.executeHelper(AmazonHttpClient.java:489)
	at com.amazonaws.http.AmazonHttpClient.execute(AmazonHttpClient.java:310)
	at com.amazonaws.services.s3.AmazonS3Client.invoke(AmazonS3Client.java:3785)
	at com.amazonaws.services.s3.AmazonS3Client.invoke(AmazonS3Client.java:3738)
	at com.amazonaws.services.s3.AmazonS3Client.listMultipartUploads(AmazonS3Client.java:2796)
	at com.amazonaws.services.s3.transfer.TransferManager.abortMultipartUploads(TransferManager.java:1217)
	at org.apache.hadoop.fs.s3a.S3AFileSystem.initMultipartUploads(S3AFileSystem.java:454)
	at org.apache.hadoop.fs.s3a.S3AFileSystem.initialize(S3AFileSystem.java:289)
	at org.apache.hadoop.fs.FileSystem.createFileSystem(FileSystem.java:2715)
	at org.apache.hadoop.fs.FileSystem.access$200(FileSystem.java:96)
	at org.apache.hadoop.fs.FileSystem$Cache.getInternal(FileSystem.java:2749)
	at org.apache.hadoop.fs.FileSystem$Cache.getUnique(FileSystem.java:2737)
	at org.apache.hadoop.fs.FileSystem.newInstance(FileSystem.java:430)
```

1. Use the [Specific endpoint of the bucket's S3 service](http://docs.aws.amazon.com/general/latest/gr/rande.html#s3_region)
1. If not using "V4" authentication (see above), the original S3 endpoint
can be used:

```
    <property>
      <name>fs.s3a.endpoint</name>
      <value>s3.amazonaws.com</value>
    </property>
```

Using the explicit endpoint for the region is recommended for speed and the
ability to use the V4 signing API.

## Visible S3 Inconsistency

Amazon S3 is *an eventually consistent object store*. That is: not a filesystem.

It offers read-after-create consistency: a newly created file is immediately
visible. Except, there is a small quirk: a negative GET may be cached, such
that even if an object is immediately created, the fact that there "wasn't"
an object is still remembered.

That means the following sequence on its own will be consistent
```
touch(path) -> getFileStatus(path)
```

But this sequence *may* be inconsistent.

```
getFileStatus(path) -> touch(path) -> getFileStatus(path)
```

A common source of visible inconsistencies is that the S3 metadata
database —the part of S3 which serves list requests— is updated asynchronously.
Newly added or deleted files may not be visible in the index, even though direct
operations on the object (`HEAD` and `GET`) succeed.

In S3A, that means the `getFileStatus()` and `open()` operations are more likely
to be consistent with the state of the object store than any directory list
operations (`listStatus()`, `listFiles()`, `listLocatedStatus()`,
`listStatusIterator()`).


### `FileNotFoundException` even though the file was just written.

This can be a sign of consistency problems. It may also surface if there is some
asynchronous file write operation still in progress in the client: the operation
has returned, but the write has not yet completed. While the S3A client code
does block during the `close()` operation, we suspect that asynchronous writes
may be taking place somewhere in the stack —this could explain why parallel tests
fail more often than serialized tests.

### File not found in a directory listing, even though `getFileStatus()` finds it

(Similarly: deleted file found in listing, though `getFileStatus()` reports
that it is not there)

This is a visible sign of updates to the metadata server lagging
behind the state of the underlying filesystem.


### File not visible/saved

The files in an object store are not visible until the write has been completed.
In-progress writes are simply saved to a local file/cached in RAM and only uploaded.
at the end of a write operation. If a process terminated unexpectedly, or failed
to call the `close()` method on an output stream, the pending data will have
been lost.

### File `flush()` and `hflush()` calls do not save data to S3A

Again, this is due to the fact that the data is cached locally until the
`close()` operation. The S3A filesystem cannot be used as a store of data
if it is required that the data is persisted durably after every
`flush()/hflush()` call. This includes resilient logging, HBase-style journalling
and the like. The standard strategy here is to save to HDFS and then copy to S3.

### Other issues

*Performance slow*

S3 is slower to read data than HDFS, even on virtual clusters running on
Amazon EC2.

* HDFS replicates data for faster query performance
* HDFS stores the data on the local hard disks, avoiding network traffic
 if the code can be executed on that host. As EC2 hosts often have their
 network bandwidth throttled, this can make a tangible difference.
* HDFS is significantly faster for many "metadata" operations: listing
the contents of a directory, calling `getFileStatus()` on path,
creating or deleting directories.
* On HDFS, Directory renames and deletes are `O(1)` operations. On
S3 renaming is a very expensive `O(data)` operation which may fail partway through
in which case the final state depends on where the copy+ delete sequence was when it failed.
All the objects are copied, then the original set of objects are deleted, so
a failure should not lose data —it may result in duplicate datasets.
* Because the write only begins on a `close()` operation, it may be in the final
phase of a process where the write starts —this can take so long that some things
can actually time out.
* File IO performing many seek calls/positioned read calls will encounter
performance problems due to the size of the HTTP requests made. On S3a,
the (experimental) fadvise policy "random" can be set to alleviate this at the
expense of sequential read performance and bandwidth.

The slow performance of `rename()` surfaces during the commit phase of work,
including

* The MapReduce FileOutputCommitter.
* DistCp's rename after copy operation.

Both these operations can be significantly slower when S3 is the destination
compared to HDFS or other "real" filesystem.

*Improving S3 load-balancing behavior*

Amazon S3 uses a set of front-end servers to provide access to the underlying data.
The choice of which front-end server to use is handled via load-balancing DNS
service: when the IP address of an S3 bucket is looked up, the choice of which
IP address to return to the client is made based on the the current load
of the front-end servers.

Over time, the load across the front-end changes, so those servers considered
"lightly loaded" will change. If the DNS value is cached for any length of time,
your application may end up talking to an overloaded server. Or, in the case
of failures, trying to talk to a server that is no longer there.

And by default, for historical security reasons in the era of applets,
the DNS TTL of a JVM is "infinity".

To work with AWS better, set the DNS time-to-live of an application which
works with S3 to something lower. See [AWS documentation](http://docs.aws.amazon.com/AWSSdkDocsJava/latest/DeveloperGuide/java-dg-jvm-ttl.html).


## Testing the S3 filesystem clients

Due to eventual consistency, tests may fail without reason. Transient
failures, which no longer occur upon rerunning the test, should thus be ignored.

To test the S3* filesystem clients, you need to provide two files
which pass in authentication details to the test runner

1. `auth-keys.xml`
1. `core-site.xml`

These are both Hadoop XML configuration files, which must be placed into
`hadoop-tools/hadoop-aws/src/test/resources`.

### `core-site.xml`

This file pre-exists and sources the configurations created
under `auth-keys.xml`.

For most purposes you will not need to edit this file unless you
need to apply a specific, non-default property change during the tests.

### `auth-keys.xml`

The presence of this file triggers the testing of the S3 classes.

Without this file, *none of the tests in this module will be executed*

The XML file must contain all the ID/key information needed to connect
each of the filesystem clients to the object stores, and a URL for
each filesystem for its testing.

1. `test.fs.s3n.name` : the URL of the bucket for S3n tests
1. `test.fs.s3a.name` : the URL of the bucket for S3a tests
2. `test.fs.s3.name` : the URL of the bucket for "S3"  tests

The contents of each bucket will be destroyed during the test process:
do not use the bucket for any purpose other than testing. Furthermore, for
s3a, all in-progress multi-part uploads to the bucket will be aborted at the
start of a test (by forcing `fs.s3a.multipart.purge=true`) to clean up the
temporary state of previously failed tests.

Example:

    <configuration>
      
      <property>
        <name>test.fs.s3n.name</name>
        <value>s3n://test-aws-s3n/</value>
      </property>
    
      <property>
        <name>test.fs.s3a.name</name>
        <value>s3a://test-aws-s3a/</value>
      </property>
    
      <property>
        <name>test.fs.s3.name</name>
        <value>s3://test-aws-s3/</value>
      </property>
  
      <property>
        <name>fs.s3.awsAccessKeyId</name>
        <value>DONOTPCOMMITTHISKEYTOSCM</value>
      </property>

      <property>
        <name>fs.s3.awsSecretAccessKey</name>
        <value>DONOTEVERSHARETHISSECRETKEY!</value>
      </property>

      <property>
        <name>fs.s3n.awsAccessKeyId</name>
        <value>DONOTPCOMMITTHISKEYTOSCM</value>
      </property>

      <property>
        <name>fs.s3n.awsSecretAccessKey</name>
        <value>DONOTEVERSHARETHISSECRETKEY!</value>
      </property>

      <property>
        <name>fs.s3a.access.key</name>
        <description>AWS access key ID. Omit for IAM role-based authentication.</description>
        <value>DONOTCOMMITTHISKEYTOSCM</value>
      </property>
  
      <property>
        <name>fs.s3a.secret.key</name>
        <description>AWS secret key. Omit for IAM role-based authentication.</description>
        <value>DONOTEVERSHARETHISSECRETKEY!</value>
      </property>

      <property>
        <name>test.sts.endpoint</name>
        <description>Specific endpoint to use for STS requests.</description>
        <value>sts.amazonaws.com</value>
      </property>

    </configuration>

### File `contract-test-options.xml`

The file `hadoop-tools/hadoop-aws/src/test/resources/contract-test-options.xml`
must be created and configured for the test filesystems.

If a specific file `fs.contract.test.fs.*` test path is not defined for
any of the filesystems, those tests will be skipped.

The standard S3 authentication details must also be provided. This can be
through copy-and-paste of the `auth-keys.xml` credentials, or it can be
through direct XInclude inclusion.

### s3://

The filesystem name must be defined in the property `fs.contract.test.fs.s3`. 


Example:

      <property>
        <name>fs.contract.test.fs.s3</name>
        <value>s3://test-aws-s3/</value>
      </property>

### s3n://


In the file `src/test/resources/contract-test-options.xml`, the filesystem
name must be defined in the property `fs.contract.test.fs.s3n`.
The standard configuration options to define the S3N authentication details
must also be provided.

Example:

      <property>
        <name>fs.contract.test.fs.s3n</name>
        <value>s3n://test-aws-s3n/</value>
      </property>

### s3a://


In the file `src/test/resources/contract-test-options.xml`, the filesystem
name must be defined in the property `fs.contract.test.fs.s3a`.
The standard configuration options to define the S3N authentication details
must also be provided.

Example:

    <property>
      <name>fs.contract.test.fs.s3a</name>
      <value>s3a://test-aws-s3a/</value>
    </property>

### Complete example of `contract-test-options.xml`



    <?xml version="1.0"?>
    <?xml-stylesheet type="text/xsl" href="configuration.xsl"?>
    <!--
      ~ Licensed to the Apache Software Foundation (ASF) under one
      ~  or more contributor license agreements.  See the NOTICE file
      ~  distributed with this work for additional information
      ~  regarding copyright ownership.  The ASF licenses this file
      ~  to you under the Apache License, Version 2.0 (the
      ~  "License"); you may not use this file except in compliance
      ~  with the License.  You may obtain a copy of the License at
      ~
      ~       http://www.apache.org/licenses/LICENSE-2.0
      ~
      ~  Unless required by applicable law or agreed to in writing, software
      ~  distributed under the License is distributed on an "AS IS" BASIS,
      ~  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
      ~  See the License for the specific language governing permissions and
      ~  limitations under the License.
      -->
    
    <configuration>
    
      <include xmlns="http://www.w3.org/2001/XInclude"
        href="/home/testuser/.ssh/auth-keys.xml"/>
    
      <property>
        <name>fs.contract.test.fs.s3</name>
        <value>s3://test-aws-s3/</value>
      </property>


      <property>
        <name>fs.contract.test.fs.s3a</name>
        <value>s3a://test-aws-s3a/</value>
      </property>

      <property>
        <name>fs.contract.test.fs.s3n</name>
        <value>s3n://test-aws-s3n/</value>
      </property>

    </configuration>

This example pulls in the `~/.ssh/auth-keys.xml` file for the credentials.
This provides one single place to keep the keys up to date —and means
that the file `contract-test-options.xml` does not contain any
secret credentials itself. As the auth keys XML file is kept out of the
source code tree, it is not going to get accidentally committed.


### Running the Tests

After completing the configuration, execute the test run through Maven.

    mvn clean test

It's also possible to execute multiple test suites in parallel by enabling the
`parallel-tests` Maven profile.  The tests spend most of their time blocked on
network I/O with the S3 service, so running in parallel tends to complete full
test runs faster.

    mvn -Pparallel-tests clean test

Some tests must run with exclusive access to the S3 bucket, so even with the
`parallel-tests` profile enabled, several test suites will run in serial in a
separate Maven execution step after the parallel tests.

By default, the `parallel-tests` profile runs 4 test suites concurrently.  This
can be tuned by passing the `testsThreadCount` argument.

    mvn -Pparallel-tests -DtestsThreadCount=8 clean test

### Testing against different regions

S3A can connect to different regions —the tests support this. Simply
define the target region in `contract-tests.xml` or any `auth-keys.xml`
file referenced.

```xml
<property>
  <name>fs.s3a.endpoint</name>
  <value>s3.eu-central-1.amazonaws.com</value>
</property>
```
This is used for all tests expect for scale tests using a Public CSV.gz file
(see below)

### S3A session tests

The test `TestS3ATemporaryCredentials` requests a set of temporary
credentials from the STS service, then uses them to authenticate with S3.

If an S3 implementation does not support STS, then the functional test
cases must be disabled:

        <property>
          <name>test.fs.s3a.sts.enabled</name>
          <value>false</value>
        </property>

These tests reqest a temporary set of credentials from the STS service endpoint.
An alternate endpoint may be defined in `test.fs.s3a.sts.endpoint`.

        <property>
          <name>test.fs.s3a.sts.endpoint</name>
          <value>https://sts.example.org/</value>
        </property>

The default is ""; meaning "use the amazon default value".

#### CSV Data source Tests

The `TestS3AInputStreamPerformance` tests require read access to a multi-MB
text file. The default file for these tests is one published by amazon,
[s3a://landsat-pds.s3.amazonaws.com/scene_list.gz](http://landsat-pds.s3.amazonaws.com/scene_list.gz).
This is a gzipped CSV index of other files which amazon serves for open use.

The path to this object is set in the option `fs.s3a.scale.test.csvfile`,

    <property>
      <name>fs.s3a.scale.test.csvfile</name>
      <value>s3a://landsat-pds/scene_list.gz</value>
    </property>

1. If the option is not overridden, the default value is used. This
is hosted in Amazon's US-east datacenter.
1. If `fs.s3a.scale.test.csvfile` is empty, tests which require it will be skipped.
1. If the data cannot be read for any reason then the test will fail.
1. If the property is set to a different path, then that data must be readable
and "sufficiently" large.

To test on different S3 endpoints, or alternate infrastructures supporting
the same APIs, the option `fs.s3a.scale.test.csvfile` must either be
set to " ", or an object of at least 10MB is uploaded to the object store, and
the `fs.s3a.scale.test.csvfile` option set to its path.

```xml
<property>
  <name>fs.s3a.scale.test.csvfile</name>
  <value> </value>
</property>
```

(the reason the space or newline is needed is to add "an empty entry"; an empty
`<value/>` would be considered undefined and pick up the default)

*Note:* if using a test file in an S3 region requiring a different endpoint value
set in `fs.s3a.endpoint`, define it in `fs.s3a.scale.test.csvfile.endpoint`.
If the default CSV file is used, the tests will automatically use the us-east
endpoint:

```xml
<property>
  <name>fs.s3a.scale.test.csvfile.endpoint</name>
  <value>s3.amazonaws.com</value>
</property>
```

#### Scale test operation count

Some scale tests perform multiple operations (such as creating many directories).

The exact number of operations to perform is configurable in the option
`scale.test.operation.count`

      <property>
        <name>scale.test.operation.count</name>
        <value>10</value>
      </property>

Larger values generate more load, and are recommended when testing locally,
or in batch runs.

Smaller values results in faster test runs, especially when the object
store is a long way away.

Operations which work on directories have a separate option: this controls
the width and depth of tests creating recursive directories. Larger
values create exponentially more directories, with consequent performance
impact.

      <property>
        <name>scale.test.directory.count</name>
        <value>2</value>
      </property>

DistCp tests targeting S3A support a configurable file size.  The default is
10 MB, but the configuration value is expressed in KB so that it can be tuned
smaller to achieve faster test runs.

      <property>
        <name>scale.test.distcp.file.size.kb</name>
        <value>10240</value>
      </property>


### Testing against non AWS S3 endpoints.

The S3A filesystem is designed to work with storage endpoints which implement
the S3 protocols to the extent that the amazon S3 SDK is capable of talking
to it. We encourage testing against other filesystems and submissions of patches
which address issues. In particular, we encourage testing of Hadoop release
candidates, as these third-party endpoints get even less testing than the
S3 endpoint itself.


**Disabling the encryption tests**

If the endpoint doesn't support server-side-encryption, these will fail

      <property>
        <name>test.fs.s3a.encryption.enabled</name>
        <value>false</value>
      </property>

Encryption is only used for those specific test suites with `Encryption` in
their classname.
