/*
 * Copyright 2015 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.google.deploy.validators

import com.netflix.spinnaker.clouddriver.deploy.DescriptionValidator
import com.netflix.spinnaker.clouddriver.deploy.ValidationErrors
import com.netflix.spinnaker.clouddriver.google.GoogleOperation
import com.netflix.spinnaker.clouddriver.google.deploy.description.UpsertGoogleAutoscalingPolicyDescription
import com.netflix.spinnaker.clouddriver.google.security.GoogleNamedAccountCredentials
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperations
import com.netflix.spinnaker.credentials.CredentialsRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@GoogleOperation(AtomicOperations.RESIZE_SERVER_GROUP)
@Component("resizeGoogleServerGroupDescriptionValidator")
class ResizeGoogleServerGroupDescriptionValidator extends DescriptionValidator<Object> {
  @Autowired
  CredentialsRepository<GoogleNamedAccountCredentials> credentialsRepository

  @Override
  void validate(List priorDescriptions, def description, ValidationErrors errors) {
    // If the target server group has an Autoscaler configured the converter will return an
    // UpsertGoogleAutoscalingPolicyDescription instead of a ResizeGoogleServerGroupDescription.
    if (description in UpsertGoogleAutoscalingPolicyDescription) {
      new UpsertGoogleAutoscalingPolicyDescriptionValidator(credentialsRepository: credentialsRepository)
        .validate(priorDescriptions, description, errors)
    } else {
      def helper = new StandardGceAttributeValidator("resizeGoogleServerGroupDescription", errors)

      helper.validateCredentials(description.accountName, credentialsRepository)
      helper.validateServerGroupName(description.serverGroupName)
      helper.validateNotEmpty(description.targetSize ?: description.capacity?.desired, "targetSize")
      helper.validateNonNegativeLong(description.targetSize ?: description.capacity?.desired ?: 0, "targetSize")
      helper.validateRegion(description.region, description.credentials)
    }
  }
}
