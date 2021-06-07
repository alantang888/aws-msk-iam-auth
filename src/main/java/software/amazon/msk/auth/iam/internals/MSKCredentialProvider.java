/*
  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.

  Licensed under the Apache License, Version 2.0 (the "License").
  You may not use this file except in compliance with the License.
  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
*/
package software.amazon.msk.auth.iam.internals;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSCredentialsProviderChain;
import com.amazonaws.auth.EC2ContainerCredentialsProviderWrapper;
import com.amazonaws.auth.EnvironmentVariableCredentialsProvider;
import com.amazonaws.auth.STSAssumeRoleSessionCredentialsProvider;
import com.amazonaws.auth.SystemPropertiesCredentialsProvider;
import com.amazonaws.auth.WebIdentityTokenCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * This AWS Credential Provider is used to load up AWS Credentials based on options provided on the Jaas config line.
 * As as an example
 * sasl.jaas.config = IAMLoginModule required awsProfileName={profile name};
 * The currently supported options are:
 * 1. A particular AWS Credential profile: awsProfileName={profile name}
 * 2. If no options is provided, the DefaultAWSCredentialsProviderChain is used.
 * The DefaultAWSCredentialProviderChain can be pointed to credentials in many different ways:
 * <a href="https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html">Working with AWS Credentials</a>
 */
public class MSKCredentialProvider implements AWSCredentialsProvider, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(MSKCredentialProvider.class);
    private static final String AWS_PROFILE_NAME_KEY = "awsProfileName";
    private static final String AWS_ROLE_ARN_KEY = "awsRoleArn";
    private static final String AWS_ROLE_SESSION_KEY = "awsRoleSessionName";

    private final Optional<STSAssumeRoleSessionCredentialsProvider> stsRoleProvider;
    private final AWSCredentialsProvider delegate;

    public MSKCredentialProvider(Map<String, ?> options) {
        this(options, getProfileProvider(options), getStsRoleProvider(options));
    }

    MSKCredentialProvider(Map<String, ?> options,
            Optional<EnhancedProfileCredentialsProvider> profileCredentialsProvider) {
        this(options, profileCredentialsProvider, Optional.empty());
    }

    MSKCredentialProvider(Map<String, ?> options,
            Optional<EnhancedProfileCredentialsProvider> profileCredentialsProvider,
            Optional<STSAssumeRoleSessionCredentialsProvider> roleSessionCredentialsProvider) {
        stsRoleProvider = roleSessionCredentialsProvider;
        final List delegateList = getListOfDelegates(profileCredentialsProvider, roleSessionCredentialsProvider);
        delegate = new AWSCredentialsProviderChain(delegateList);
        if (log.isDebugEnabled()) {
            log.debug("Number of options to configure credential provider {}", options.size());
        }

    }

    private List getListOfDelegates(Optional<EnhancedProfileCredentialsProvider> profileCredentialsProvider,
            Optional<STSAssumeRoleSessionCredentialsProvider> roleSessionCredentialsProvider) {
        final List delegateList = new ArrayList<>();
        profileCredentialsProvider.ifPresent(delegateList::add);
        roleSessionCredentialsProvider.ifPresent(delegateList::add);
        delegateList.add(getDefaultProvider());
        return delegateList;
    }

    //We want to override the ProfileCredentialsProvider with the EnhancedProfileCredentialsProvider
    protected AWSCredentialsProviderChain getDefaultProvider() {
        return new AWSCredentialsProviderChain(new EnvironmentVariableCredentialsProvider(),
                new SystemPropertiesCredentialsProvider(),
                WebIdentityTokenCredentialsProvider.create(),
                new EnhancedProfileCredentialsProvider(),
                new EC2ContainerCredentialsProviderWrapper());
    }

    private static Optional<EnhancedProfileCredentialsProvider> getProfileProvider(Map<String, ?> options) {
        return Optional.ofNullable(options.get(AWS_PROFILE_NAME_KEY)).map(p -> {
            if (log.isDebugEnabled()) {
                log.debug("Profile name {}", p);
            }
            return new EnhancedProfileCredentialsProvider((String) p);
        });
    }

    private static Optional<STSAssumeRoleSessionCredentialsProvider> getStsRoleProvider(Map<String, ?> options) {
        return Optional.ofNullable(options.get(AWS_ROLE_ARN_KEY)).map(p -> {
            if (log.isDebugEnabled()) {
                log.debug("Role ARN {}", p);
            }
            String sessionName = Optional.ofNullable((String) options.get(AWS_ROLE_SESSION_KEY))
                    .orElse("aws-msk-iam-auth");
            return new STSAssumeRoleSessionCredentialsProvider.Builder((String) p, sessionName).build();
        });
    }

    @Override
    public AWSCredentials getCredentials() {
        return delegate.getCredentials();
    }

    @Override
    public void refresh() {
        delegate.refresh();
    }

    @Override
    public void close() {
        stsRoleProvider.ifPresent(STSAssumeRoleSessionCredentialsProvider::close);
    }
}
