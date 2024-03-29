/*
 * Copyright 2016 The original authors.
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
/*
 * Copyright 2016 The original authors.
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
package com.netflix.spinnaker.clouddriver.azure.templates

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.microsoft.azure.management.compute.ResourceIdentityType
import com.microsoft.azure.management.compute.VirtualMachineScaleSetDataDisk
import com.netflix.spinnaker.clouddriver.azure.common.AzureUtilities
import com.netflix.spinnaker.clouddriver.azure.resources.loadbalancer.model.AzureLoadBalancer
import com.netflix.spinnaker.clouddriver.azure.resources.servergroup.model.AzureServerGroupDescription
import com.netflix.spinnaker.clouddriver.azure.resources.servergroup.model.AzureServerGroupDescription.AzureInboundPortConfig
import groovy.util.logging.Slf4j

@Slf4j
class AzureServerGroupResourceTemplate {
  static final String STORAGE_ACCOUNT_SUFFIX = "sa"
  static String LB_NAME = null

  protected static ObjectMapper mapper = new ObjectMapper()
    .configure(SerializationFeature.INDENT_OUTPUT, true)
    .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)

  /**
   * Build the resource manager template that will create the Azure equivalent (VM Scale Set)
   * of the Spinnaker Server Group
   * @param description - Description object containing the values to be specified in the template
   * @return - JSON string representing the Resource Manager template for a Azure VM Scale Set (Server Group)
   */
  static String getTemplate(AzureServerGroupDescription description) {
    ServerGroupTemplate template = new ServerGroupTemplate(description)
    mapper.writeValueAsString(template)
  }

  /**
   * Initialize variables that will be used in mulitple places
   * @param description Azure Server Group description object
   */
  private static void initializeCommonVariables(AzureServerGroupDescription description) {
    LB_NAME = AzureUtilities.LB_NAME_PREFIX + description.name
  }

  /**
   *
   */
  static class ServerGroupTemplate {
    //TODO: Make this configurable for AZURE_US_GOVERNMENT
    String $schema = "https://schema.management.azure.com/schemas/2015-01-01/deploymentTemplate.json#"
    String contentVersion = "1.0.0.0"

    ServerGroupTemplateParameters parameters
    TemplateVariables variables
    ArrayList<Resource> resources = []

    /**
     *
     * @param description
     */
    ServerGroupTemplate(AzureServerGroupDescription description) {
      if (description.enableInboundNAT){
        initializeCommonVariables(description)
        resources.add(new PublicIpResource(properties: new PublicIPPropertiesWithDns()))
        resources.add(new LoadBalancer(description))
      }

      parameters = new ServerGroupTemplateParameters()

      //If it's custom,
      if (description.image.isCustom) {
        variables = new CoreServerGroupTemplateVariables(description)
      } else {
        variables = new ExtendedServerGroupTemplateVariables(description)
        resources.add(new StorageAccount(description))
      }

      resources.add(new VirtualMachineScaleSet(description))
    }

  }

  interface TemplateVariables {}

  static class CoreServerGroupTemplateVariables implements TemplateVariables {
    final String apiVersion = "2019-03-01"
    String publicIPAddressName = ""
    String publicIPAddressID = ""
    String publicIPAddressType = ""
    String dnsNameForLBIP = ""
    String loadBalancerBackend = ""
    String loadBalancerFrontEnd = ""
    String loadBalancerName = ""
    String loadBalancerID = ""
    String frontEndIPConfigID = ""
    String inboundNatPoolName = ""

    CoreServerGroupTemplateVariables() {}

    CoreServerGroupTemplateVariables(AzureServerGroupDescription description) {
      if(description.enableInboundNAT){
        publicIPAddressName = AzureUtilities.PUBLICIP_NAME_PREFIX + description.name
        publicIPAddressID = "[resourceId('Microsoft.Network/publicIPAddresses', variables('publicIPAddressName'))]"
        publicIPAddressType = "Dynamic"
        dnsNameForLBIP = AzureUtilities.DNS_NAME_PREFIX + description.name.toLowerCase()
        frontEndIPConfigID = "[resourceId('Microsoft.Network/loadBalancers/frontendIPConfigurations/', variables('loadBalancerName'), variables('loadBalancerFrontEnd'))]"
        loadBalancerFrontEnd = AzureUtilities.LBFRONTEND_NAME_PREFIX + description.name
        loadBalancerBackend = AzureUtilities.LBBACKEND_NAME_PREFIX + description.name
        loadBalancerName = LB_NAME
        loadBalancerID = "[resourceId('Microsoft.Network/loadBalancers', variables('loadBalancerName'))]"
        inboundNatPoolName = AzureUtilities.INBOUND_NATPOOL_PREFIX + description.name
      }
    }
  }

  /**
   *
   */
  static class ExtendedServerGroupTemplateVariables extends CoreServerGroupTemplateVariables implements TemplateVariables {
    // The values of these variables need to match the name of the corresponding variables below
    // The reason is that in other sections of the RM template (i.e., Resources) their is a reference by name
    // to the variable defined in Variables section of the template.
    // These *Var variables are meant to help keep that reference from breaking IF the name of the actual variable
    // changes
    static transient String uniqueStorageNamesArrayVar = 'uniqueStorageNameArray'
    static transient String vhdContainerNameVar = 'vhdContainerName'

    String vhdContainerName
    OsType osType
    String imageReference
    ArrayList<String> uniqueStorageNameArray = []

    /**
     *
     * @param description
     */
    ExtendedServerGroupTemplateVariables(AzureServerGroupDescription description) {
      super(description)

      vhdContainerName = description.name.toLowerCase()
      osType = new OsType(description)
      imageReference = "[variables('osType')]"

      for (int i = 0; i < description.getStorageAccountCount(); i++) {
        uniqueStorageNameArray.add(getUniqueStorageName(description.name, i))
      }
    }
  }

  static String getUniqueStorageName(String name, long idx) {
    String noDashName = name.replaceAll("-", "").toLowerCase()
    "[concat(uniqueString(concat(resourceGroup().id, subscription().id, '$noDashName', '$idx')), '$STORAGE_ACCOUNT_SUFFIX')]"
  }

  /**
   *
   */
  static class ServerGroupTemplateParameters {
    LocationParameter location = new LocationParameter(["description": "Location to deploy"])
    SubnetParameter subnetId = new SubnetParameter(["description": "Subnet Resource ID"], "")
    AppGatewayAddressPoolParameter appGatewayAddressPoolId = new AppGatewayAddressPoolParameter(["description": "App Gateway backend address pool resource ID"], "")
    VMUserNameParameter vmUserName = new VMUserNameParameter(["description": "Admin username on all VMs"], "")
    VMPasswordParameter vmPassword = new VMPasswordParameter(["description": "Admin password on all VMs"], "")
    VMSshPublicKeyParameter vmSshPublicKey = new VMSshPublicKeyParameter(["description": "SSH public key on all VMs"], "")
    LoadBalancerPoolParameter loadBalancerAddressPoolId = new LoadBalancerPoolParameter(["description": "Load balancer pool ID"], "")
    LoadBalancerNatPoolParameter loadBalancerNatPoolId = new LoadBalancerNatPoolParameter(["description": "Load balancer NAT pool ID"], "")

    // The default value of custom data cannot be "" otherwise Azure service will run into error complaining "custom data must be in Base64".
    CustomDataParameter customData = new CustomDataParameter(["description":"custom data to pass down to the virtual machine(s)"], "sample custom data")
  }

  /* Server Group Parameters */
  static String subnetParameterName = "subnetId"
  static class SubnetParameter extends StringParameterWithDefault {
    SubnetParameter(Map<String, String> metadata, String defaultValue) {
      super(metadata, defaultValue)
    }
  }

  static String locationParameterName = "location"
  static class LocationParameter extends StringParameter {
    LocationParameter(Map<String, String> metadata) {
      super(metadata)
    }
  }

  static String appGatewayAddressPoolParameterName = "appGatewayAddressPoolId"
  static class AppGatewayAddressPoolParameter extends StringParameterWithDefault {
    AppGatewayAddressPoolParameter(Map<String, String> metadata, String defaultValue) {
      super(metadata, defaultValue)
    }
  }

  static String loadBalancerAddressPoolParameterName = "loadBalancerAddressPoolId"
  static class LoadBalancerPoolParameter extends StringParameterWithDefault {
    LoadBalancerPoolParameter(Map<String, String> metadata, String defaultValue) {
      super(metadata, defaultValue)
    }
  }

  static String loadBalancerNatPoolParameterName = "loadBalancerNatPoolId"
  static class LoadBalancerNatPoolParameter extends StringParameterWithDefault {
    LoadBalancerNatPoolParameter(Map<String, String> metadata, String defaultValue) {
      super(metadata, defaultValue)
    }
  }

  static String customDataParameterName = "customData"
  static class CustomDataParameter extends StringParameterWithDefault {
    CustomDataParameter(Map<String, String> metadata, String defValue) {
      super(metadata, defValue)
    }
  }

  static String vmUserNameParameterName = "vmUserName"
  static class VMUserNameParameter extends SecureStringParameter {
    VMUserNameParameter(Map<String, String> metadata, String defaultValue) {
      super(metadata, defaultValue)
    }
  }

  static String vmPasswordParameterName = "vmPassword"
  static class VMPasswordParameter extends SecureStringParameter {
    VMPasswordParameter(Map<String, String> metadata, String defaultValue) {
      super(metadata, defaultValue)
    }
  }

  static String vmSshPublicKeyParameterName = "vmSshPublicKey"
  static class VMSshPublicKeyParameter extends SecureStringParameter {
    VMSshPublicKeyParameter(Map<String, String> metadata, String defaultValue) {
      super(metadata, defaultValue)
    }
  }

  /**
   *
   */
  static class OsType {
    String publisher
    String offer
    String sku
    String version

    /**
     *
     * @param description
     */
    OsType(AzureServerGroupDescription description) {
      publisher = description.image.publisher
      offer = description.image.offer
      sku = description.image.sku
      version = description.image.version
    }
  }

  /**
   *
   */
  static class StorageAccount extends Resource {
    CopyOperation copy
    StorageAccountProperties properties

    /**
     *
     * @param description
     */
    StorageAccount(AzureServerGroupDescription description) {
      apiVersion = "[variables('apiVersion')]"
      name = String.format("[concat(variables('%s')[copyIndex()])]", ExtendedServerGroupTemplateVariables.uniqueStorageNamesArrayVar)
      type = "Microsoft.Storage/storageAccounts"
      location = "[parameters('${locationParameterName}')]"
      def currentTime = System.currentTimeMillis()

      copy = new CopyOperation("storageLoop", description.getStorageAccountCount())
      tags = [:]
      tags.appName = description.application
      tags.stack = description.stack
      tags.detail = description.detail
      tags.cluster = description.clusterName
      tags.serverGroupName = description.name
      tags.createdTime = currentTime.toString()

      properties = new StorageAccountProperties()
    }
  }

  /**
   *
   */
  static class StorageAccountProperties {
    String accountType

    /**
     *
     * @param description
     */
    StorageAccountProperties() {
      // Hardcoded value to use premium storage for better perf per Azure recommendation;
      // TODO: we will revisit this later given that premium storage is a bit more expensive than standard
      accountType = "Premium_LRS"
    }
  }

  /**
   *
   */
  static class CopyOperation {
    String name
    int count

    /**
     *
     * @param operationName
     * @param iterations
     */
    CopyOperation(String operationName, int iterations) {
      name = operationName
      count = iterations
    }
  }

  /**
   *
   */
  static class VirtualMachineScaleSet extends DependingResource {
    ScaleSetSkuProperty sku
    VirtualMachineScaleSetProperty properties
    ManagedIdentity identity

    @JsonInclude(JsonInclude.Include.NON_NULL)
    List<String> zones

    VirtualMachineScaleSet(AzureServerGroupDescription description) {
      apiVersion = "[variables('apiVersion')]"
      name = description.name
      type = "Microsoft.Compute/virtualMachineScaleSets"
      location = "[parameters('${locationParameterName}')]"

      String userAssignedIdentities = description.userAssignedIdentities
      if (!userAssignedIdentities?.trim()){
        // If the userAssignedIdentities is null or empty just attempt to create a system managed identity if it is enabled
        identity = new ManagedIdentity(description.useSystemManagedIdentity)
      }else{
        // else create an user assigned identity with optional system managed identity (if it is enabled)
        identity = new UserAndOptionalSystemAssignedIdentity(description.useSystemManagedIdentity, userAssignedIdentities)
      }

      def currentTime = System.currentTimeMillis()
      tags = [:]
      tags.createdTime = currentTime.toString()
      if (description.subnetId) tags.subnetId = description.subnetId
      if (description.securityGroupName) tags.securityGroupName = description.securityGroupName

      if (description.instanceTags != null) {
        tags << description.instanceTags
      }

      // debug only; can be removed as part of the tags cleanup
      if (description.appGatewayName) tags.appGatewayName = description.appGatewayName
      if (description.loadBalancerName) tags.loadBalancerName = description.loadBalancerName
      // will need this when cloning a server group
      if (description.image.imageName) tags.imageName = description.image.imageName

      if(description.zones != null && description.zones.size() != 0) {
        zones = description.zones.asList()
      }

      if(description.enableInboundNAT){
        tags.enableInboundNAT = description.enableInboundNAT ? "true" : "false"
        this.dependsOn.add("[concat('Microsoft.Network/loadBalancers/', variables('loadBalancerName'))]")
      }

      properties = new VirtualMachineScaleSetProperty(description)
      sku = new ScaleSetSkuProperty(description)
    }
  }

  static class VirtualMachineScaleSetProperty {
    Map<String, String> upgradePolicy = [:]
    ScaleSetVMProfile virtualMachineProfile
    Boolean doNotRunExtensionsOnOverprovisionedVMs

    VirtualMachineScaleSetProperty(AzureServerGroupDescription description) {
      upgradePolicy["mode"] = description.upgradePolicy.toString()
      doNotRunExtensionsOnOverprovisionedVMs = description.doNotRunExtensionsOnOverprovisionedVMs

      // protocol is the only required setting in both scenarios
      // https://docs.microsoft.com/en-us/azure/virtual-machine-scale-sets/virtual-machine-scale-sets-health-extension#settings
      if (description.customScriptsSettings?.commandToExecute || description.healthSettings?.protocol) {
        virtualMachineProfile = new ScaleSetVMProfilePropertyWithExtension(description)
      }
      else {
        virtualMachineProfile = new ScaleSetVMProfileProperty(description)
      }
    }
  }

  // ***Scale Set SKU
  /**
   *
   */
  static class ScaleSetSkuProperty {
    String name
    String tier
    int capacity

    /**
     *
     * @param description
     */
    ScaleSetSkuProperty(AzureServerGroupDescription description) {
      name = description.sku.name
      tier = description.sku.tier
      capacity = description.sku.capacity
    }
  }

  // Scheduled Event Profiles
  static class ScheduledEventsProfile {
    TerminateNotificationProfile terminateNotificationProfile

    ScheduledEventsProfile(AzureServerGroupDescription description) {
      terminateNotificationProfile = new TerminateNotificationProfile(description)
    }
  }

  static class TerminateNotificationProfile {
    String notBeforeTimeout
    Boolean enable

    TerminateNotificationProfile(AzureServerGroupDescription description) {
      enable = true
      notBeforeTimeout = "PT" + description.terminationNotBeforeTimeoutInMinutes + "M"
    }
  }

  // ***Scale Set None/System Managed Identity
  static class ManagedIdentity {
    String type

    ManagedIdentity(){}
    /**
     *
     * @param description
     */
    ManagedIdentity(Boolean enableSystemAssigned) {
      type = enableSystemAssigned ? ResourceIdentityType.SYSTEM_ASSIGNED: ResourceIdentityType.NONE
    }
  }

  // ***Scale Set User assigned and optionaly system assigned Identity
  static class UserAndOptionalSystemAssignedIdentity extends ManagedIdentity {
    // user assigned identities needs to be added in the following format
    // "[resourceID('Microsoft.ManagedIdentity/userAssignedIdentities/','<identityname>')]" : { }
    Map<String, Map<String, String>> userAssignedIdentities = [:]

    /**
     *
     * @param description
     */
    UserAndOptionalSystemAssignedIdentity(Boolean enableSystemAssigned, String userAssignedIdentities) {
      type = enableSystemAssigned ? ResourceIdentityType.SYSTEM_ASSIGNED_USER_ASSIGNED.toString() : ResourceIdentityType.USER_ASSIGNED
      if (userAssignedIdentities.length() > 0) {
        for (String identity : userAssignedIdentities.split(",")) {
          this.userAssignedIdentities.put(String.format("[resourceID('Microsoft.ManagedIdentity/userAssignedIdentities/','%s')]", identity), [:])
        }
      }
    }
  }

  interface ScaleSetOsProfile {}

  // ***OSProfile
  static class ScaleSetOsProfileProperty implements ScaleSetOsProfile {
    String computerNamePrefix
    String adminUsername
    String adminPassword
    String customData

    ScaleSetOsProfileProperty(AzureServerGroupDescription description) {
      //Max length of 10 characters to allow for an aditional postfix within a max length of 15 characters
      String osType = description.image.ostype
      int maxLengthOsSpecific = osType==null||osType.isEmpty()||osType.isAllWhitespace()||osType.equalsIgnoreCase("linux")?10:9
      int identifierLength=description.getIdentifier().length()
      //A guard to avoid nullpointer in case identifierLength is less than maxLengthOsSpecific
      int identifierMaxIndex=identifierLength<maxLengthOsSpecific?identifierLength:maxLengthOsSpecific
      computerNamePrefix = description.getIdentifier().substring(0, identifierMaxIndex)
      log.info(String.format("computerNamePrefix will be truncated to %d characters to maintain Azure restrictions",maxLengthOsSpecific))
      adminUsername = "[parameters('${vmUserNameParameterName}')]"
      adminPassword = "[parameters('${vmPasswordParameterName}')]"
      customData = "[base64(parameters('customData'))]"
    }
  }

  static class ScaleSetOsProfileWindowsConfiguration extends ScaleSetOsProfileProperty implements ScaleSetOsProfile {
    OsProfileWindowsConfiguration windowsConfiguration

    ScaleSetOsProfileWindowsConfiguration(AzureServerGroupDescription description) {
      super(description)
      windowsConfiguration = new OsProfileWindowsConfiguration(description)
    }
  }

  static class OsProfileWindowsConfiguration {
    String timeZone

    OsProfileWindowsConfiguration(AzureServerGroupDescription description) {
      timeZone = description.windowsTimeZone
    }
  }

  static class ScaleSetOsProfileLinuxConfiguration extends ScaleSetOsProfileProperty implements ScaleSetOsProfile {
    OsProfileLinuxConfiguration linuxConfiguration

    ScaleSetOsProfileLinuxConfiguration(AzureServerGroupDescription description) {
      super(description)
      linuxConfiguration = new OsProfileLinuxConfiguration()
    }
  }

  static class OsProfileLinuxConfiguration{
    Boolean disablePasswordAuthentication
    ScaleSetOsProfileLinuxConfigurationSsh ssh

    OsProfileLinuxConfiguration() {
      disablePasswordAuthentication = true
      ssh = new ScaleSetOsProfileLinuxConfigurationSsh()
    }
  }

  static class ScaleSetOsProfileLinuxConfigurationSsh {
    ArrayList<ScaleSetOsProfileLinuxConfigurationSshPublicKey> publicKeys = []

    ScaleSetOsProfileLinuxConfigurationSsh() {
      publicKeys.add(new ScaleSetOsProfileLinuxConfigurationSshPublicKey())
    }
  }

  static class ScaleSetOsProfileLinuxConfigurationSshPublicKey {
    String path
    String keyData

    ScaleSetOsProfileLinuxConfigurationSshPublicKey() {
      path = "[concat('/home/', parameters('${vmUserNameParameterName}'), '/.ssh/authorized_keys')]"
      keyData = "[parameters('${vmSshPublicKeyParameterName}')]"
    }
  }

  // ***Network Profile
  static class ScaleSetNetworkProfileProperty {
    ArrayList<NetworkInterfaceConfiguration> networkInterfaceConfigurations = []

    ScaleSetNetworkProfileProperty(AzureServerGroupDescription description) {
      networkInterfaceConfigurations.add(new NetworkInterfaceConfiguration(description))
    }
  }

  static class NetworkInterfaceConfiguration {
    String name
    NetworkInterfaceConfigurationProperty properties

    /**
     *
     * @param description
     */
    NetworkInterfaceConfiguration(AzureServerGroupDescription description) {
      name = AzureUtilities.NETWORK_INTERFACE_PREFIX + description.getIdentifier()
      properties = new NetworkInterfaceConfigurationProperty(description)
    }
  }

  /**
   * Here is the location to put NSG applying to VMSS nic
   */
  static class NetworkInterfaceConfigurationProperty {
    boolean primary
    boolean enableIpForwarding
    ArrayList<NetworkInterfaceIPConfiguration> ipConfigurations = []

    /**
     *
     * @param description
     */
    NetworkInterfaceConfigurationProperty(AzureServerGroupDescription description) {
      primary = true
      enableIpForwarding = description.enableIpForwarding
      ipConfigurations.add(new NetworkInterfaceIPConfiguration(description))
    }
  }

  static class NetworkSecurityGroup {
    String id

    NetworkSecurityGroup(String name) {
      this.id = "[resourceId('Microsoft.Network/networkSecurityGroups', '${name}')]"
    }
  }

  /**
   *
   */
  static class NetworkInterfaceIPConfiguration {
    String name
    NetworkInterfaceIPConfigurationsProperty properties

    /**
     *
     * @param description
     */
    NetworkInterfaceIPConfiguration(AzureServerGroupDescription description) {
      name = AzureUtilities.IPCONFIG_NAME_PREFIX + description.getIdentifier()
      properties = new NetworkInterfaceIPConfigurationsProperty(description)
    }
  }

  /**
   *
   */
  static class NetworkInterfaceIPConfigurationsProperty {
    NetworkInterfaceIPConfigurationSubnet subnet
    ArrayList<AppGatewayBackendAddressPool> ApplicationGatewayBackendAddressPools = []
    ArrayList<LoadBalancerBackendAddressPool> loadBalancerBackendAddressPools = []
    ArrayList<LoadBalancerInboundNatPoolId> loadBalancerInboundNatPools = []

    /**
     *
     * @param description
     */
    NetworkInterfaceIPConfigurationsProperty(AzureServerGroupDescription description) {
      if(description.loadBalancerType == AzureLoadBalancer.AzureLoadBalancerType.AZURE_LOAD_BALANCER.toString()) {
        subnet = new NetworkInterfaceIPConfigurationSubnet()
        loadBalancerBackendAddressPools.add(new ExistLoadBalancerBackendAddressPool())
        loadBalancerInboundNatPools.add(new ExistLoadBalancerInboundNatPoolId())
      } else if (description.loadBalancerType == AzureLoadBalancer.AzureLoadBalancerType.AZURE_APPLICATION_GATEWAY.toString()) {
        subnet = new NetworkInterfaceIPConfigurationSubnet()
        if(description.enableInboundNAT) {
          loadBalancerBackendAddressPools.add(new LoadBalancerBackendAddressPool())
          loadBalancerInboundNatPools.add(new LoadBalancerInboundNatPoolId())
        }
        ApplicationGatewayBackendAddressPools.add(new AppGatewayBackendAddressPool())
      } else if (description.loadBalancerType == null) {
        subnet = new NetworkInterfaceIPConfigurationSubnet()
      } else {
        throw new RuntimeException("Load balancer type $description.loadBalancerType is not valid")
      }
    }
  }

  /**
   *
   */
  static class NetworkInterfaceIPConfigurationSubnet {
    String id

    NetworkInterfaceIPConfigurationSubnet() {
      id = "[parameters('${subnetParameterName}')]"
    }
  }

  static class LoadBalancerBackendAddressPool {
    String id

    LoadBalancerBackendAddressPool() {
      id = "[resourceId('Microsoft.Network/loadBalancers/backendAddressPools', variables('loadBalancerName'), variables('loadBalancerBackend'))]"
    }
  }

  static class LoadBalancerInboundNatPoolId extends IdRef {
    LoadBalancerInboundNatPoolId() {
      id = "[resourceId('Microsoft.Network/loadBalancers/inboundNatPools', variables('loadBalancerName'), variables('inboundNatPoolName'))]"
    }
  }

  static class ExistLoadBalancerBackendAddressPool {
    String id

    ExistLoadBalancerBackendAddressPool() {
      id = "[parameters('${loadBalancerAddressPoolParameterName}')]"
    }
  }

  static class ExistLoadBalancerInboundNatPoolId extends IdRef {
    ExistLoadBalancerInboundNatPoolId() {
      id = "[parameters('${loadBalancerNatPoolParameterName}')]"
    }
  }

  static class AppGatewayBackendAddressPool {
    String id

    AppGatewayBackendAddressPool() {
      id = "[parameters('${appGatewayAddressPoolParameterName}')]"
    }
  }

  interface ScaleSetVMProfile {}
  // ***VM Profile
  /**
   *
   */
  static class ScaleSetVMProfileProperty implements ScaleSetVMProfile {
    StorageProfile storageProfile
    ScaleSetOsProfile osProfile
    ScaleSetNetworkProfileProperty networkProfile
    ScheduledEventsProfile scheduledEventsProfile

    ScaleSetVMProfileProperty(AzureServerGroupDescription description) {
      storageProfile = description.image.isCustom ?
        new ScaleSetCustomManagedImageStorageProfile(description) :
        new ScaleSetStorageProfile(description)

      if (description.credentials.useSshPublicKey) {
        osProfile = new ScaleSetOsProfileLinuxConfiguration(description)
      }
      else if (description.windowsTimeZone) {
        osProfile = new ScaleSetOsProfileWindowsConfiguration(description)
      }
      else {
        osProfile = new ScaleSetOsProfileProperty(description)
      }

      if (description.terminationNotBeforeTimeoutInMinutes != null) {
        scheduledEventsProfile = new ScheduledEventsProfile(description)
      }

      networkProfile = new ScaleSetNetworkProfileProperty(description)
    }
  }

  static class ScaleSetVMProfilePropertyWithExtension extends ScaleSetVMProfileProperty implements ScaleSetVMProfile {
    ScaleSetExtensionProfileProperty extensionProfile
    ScaleSetVMProfilePropertyWithExtension(AzureServerGroupDescription description) {
      super(description)
      extensionProfile = new ScaleSetExtensionProfileProperty(description)
    }
  }

  interface OSDisk {
    String name
    String caching
    String createOption
  }

  interface StorageProfile {
    OSDisk osDisk
  }

  /**
   *
   */
  static class ScaleSetStorageProfile implements StorageProfile {

    OSDisk osDisk
    String imageReference
    List<VirtualMachineScaleSetDataDisk> dataDisks

    /**
     *
     * @param serverGroupDescription
     */
    ScaleSetStorageProfile(AzureServerGroupDescription description) {
      osDisk = new VirtualMachineOSDisk(description)
      imageReference = "[variables('imageReference')]"
      dataDisks = description.dataDisks
    }
  }


  static class ImageReference {
    String id

    ImageReference(AzureServerGroupDescription description) {
      id = description.image.uri
    }
  }

  /**
   *
   */
  static class ScaleSetCustomManagedImageStorageProfile implements StorageProfile {
    ImageReference imageReference
    List<VirtualMachineScaleSetDataDisk> dataDisks
    /**
     *
     * @param serverGroupDescription
     */
    ScaleSetCustomManagedImageStorageProfile(AzureServerGroupDescription description) {
      imageReference = new ImageReference(description)
      dataDisks = description.dataDisks
    }
  }

  static class VirtualMachineOSDisk implements OSDisk {

    String name
    String caching
    String createOption
    ArrayList<String> vhdContainers = []

    VirtualMachineOSDisk(AzureServerGroupDescription description) {
      name = "osdisk-" + description.name
      caching = "ReadOnly"
      createOption = "FromImage"
      description.getStorageAccountCount().times { idx ->
        vhdContainers.add(String.format("[concat('https://', variables('%s')[%s], '.blob.core.windows.net/', variables('%s'))]",
          ExtendedServerGroupTemplateVariables.uniqueStorageNamesArrayVar,
          idx,
          ExtendedServerGroupTemplateVariables.vhdContainerNameVar))
      }
    }
  }

  /**** VMSS extensionsProfile ****/
  static class ScaleSetExtensionProfileProperty {
    Collection<IExtensions> extensions = []

    ScaleSetExtensionProfileProperty(AzureServerGroupDescription description) {
      if (description.customScriptsSettings?.commandToExecute) {
        Collection<String> uriTemp = description.customScriptsSettings.fileUris
        if (!uriTemp || uriTemp.isEmpty() || (uriTemp.size() == 1 && !uriTemp.first()?.trim())) {

          // if there are no custom scripts provided, set the fileUris section as an empty array.
          description.customScriptsSettings.fileUris = []
        }

        extensions.add(new CustomScriptExtensions(description))
      }

      // protocol is the only required setting in both scenarios
      // https://docs.microsoft.com/en-us/azure/virtual-machine-scale-sets/virtual-machine-scale-sets-health-extension#settings
      if (description.healthSettings?.protocol) {
        extensions.add(new HealthExtensions(description))
      }
    }
  }

  interface IExtensions {
    String name
    IExtensionProperty properties
  }

  static class HealthExtensions implements IExtensions {
    String name
    HealthExtensionProperty properties

    HealthExtensions(AzureServerGroupDescription description) {
      name = description.application + "_health_ext"
      properties = new HealthExtensionProperty(description)
    }
  }

  static class CustomScriptExtensions implements IExtensions {
    String name
    CustomStringExtensionProperty properties

    CustomScriptExtensions(AzureServerGroupDescription description) {
      name = description.application + "_ext"
      properties = new CustomStringExtensionProperty(description)
    }
  }

  interface IExtensionProperty {
    String publisher
    String type
    String typeHandlerVersion // This will need to be updated every time the custom script extension major version is updated
    Boolean autoUpgradeMinorVersion = true
    IExtensionSettings settings
  }

  static class HealthExtensionProperty implements IExtensionProperty {
    String publisher
    String type
    String typeHandlerVersion // This will need to be updated every time the custom script extension major version is updated
    Boolean autoUpgradeMinorVersion = true
    HealthExtensionSettings settings

    HealthExtensionProperty(AzureServerGroupDescription description) {
      settings = new HealthExtensionSettings(description)
      publisher = AzureUtilities.AZURE_HEALTH_EXT_PUBLISHER
      type = description.image?.ostype?.toLowerCase() == "linux" ? AzureUtilities.AZURE_HEALTH_EXT_TYPE_LINUX : AzureUtilities.AZURE_HEALTH_EXT_TYPE_WINDOWS
      typeHandlerVersion = AzureUtilities.AZURE_HEALTH_EXT_VERSION
    }
  }

  static class CustomStringExtensionProperty implements IExtensionProperty {
    String publisher
    String type
    String typeHandlerVersion // This will need to be updated every time the custom script extension major version is updated
    Boolean autoUpgradeMinorVersion = true
    CustomScriptExtensionSettings settings

    CustomStringExtensionProperty(AzureServerGroupDescription description) {
      settings = new CustomScriptExtensionSettings(description)
      publisher = description.image?.ostype?.toLowerCase() == "linux" ? AzureUtilities.AZURE_CUSTOM_SCRIPT_EXT_PUBLISHER_LINUX : AzureUtilities.AZURE_CUSTOM_SCRIPT_EXT_PUBLISHER_WINDOWS
      type = description.image?.ostype?.toLowerCase() == "linux" ? AzureUtilities.AZURE_CUSTOM_SCRIPT_EXT_TYPE_LINUX: AzureUtilities.AZURE_CUSTOM_SCRIPT_EXT_TYPE_WINDOWS
      typeHandlerVersion = description.image?.ostype?.toLowerCase() == "linux" ? AzureUtilities.AZURE_CUSTOM_SCRIPT_EXT_VERSION_LINUX : AzureUtilities.AZURE_CUSTOM_SCRIPT_EXT_VERSION_WINDOWS
    }
  }

  interface IExtensionSettings {}

  static class HealthExtensionSettings implements IExtensionSettings {
    String protocol
    int port
    String requestPath

    HealthExtensionSettings(AzureServerGroupDescription description) {
      protocol = description.healthSettings.protocol
      try {
        port = Integer.parseInt(description.healthSettings.port)
      } catch (NumberFormatException ignored) {
        port = 0
        throw new IllegalArgumentException("healthSettings.port \"$description.healthSettings.port\" is not a valid integer")
      }
      requestPath = description.healthSettings.requestPath
    }
  }

  static class CustomScriptExtensionSettings implements IExtensionSettings {
    Collection<String> fileUris
    String commandToExecute

    CustomScriptExtensionSettings(AzureServerGroupDescription description) {
      commandToExecute = description.customScriptsSettings.commandToExecute
      fileUris = description.customScriptsSettings.fileUris
    }
  }

  /**** Load Balancer Resource ****/
  static class LoadBalancer extends DependingResource {
    LoadBalancerProperties properties

    LoadBalancer(AzureServerGroupDescription description) {
      apiVersion = "[variables('apiVersion')]"
      name = "[variables('loadBalancerName')]"
      type = "Microsoft.Network/loadBalancers"
      location = "[parameters('${locationParameterName}')]"
      def currentTime = System.currentTimeMillis()
      tags = [:]
      tags.appName = description.application
      tags.stack = description.stack
      tags.detail = description.detail
      tags.createdTime = currentTime.toString()
      // Mark self as an internal load balancer only for instance access
      tags.internal = "1"
      if (description.clusterName) tags.cluster = description.clusterName
      if (description.name) tags.serverGroup = description.name
      if (description.securityGroupName) tags.securityGroupName = description.securityGroupName

      this.dependsOn.add("[concat('Microsoft.Network/publicIPAddresses/', variables('publicIPAddressName'))]")

      properties = new LoadBalancerProperties(description)
    }
  }

  static class LoadBalancerProperties {
    ArrayList<FrontEndIpConfiguration> frontendIPConfigurations = []
    ArrayList<BackEndAddressPool> backendAddressPools = []
    ArrayList<InboundNatPool> inboundNatPools = []

    LoadBalancerProperties(AzureServerGroupDescription description) {
      frontendIPConfigurations.add(new FrontEndIpConfiguration())
      backendAddressPools.add(new BackEndAddressPool())
      description.inboundPortConfigs?.each {
        inboundNatPools.add(new InboundNatPool(it))
      }
    }
  }

  static class FrontEndIpConfiguration {
    String name
    FrontEndIpProperties properties

    FrontEndIpConfiguration() {
      name = "[variables('loadBalancerFrontEnd')]"
      properties = new FrontEndIpProperties("[variables('publicIPAddressID')]")
    }
  }

  static class FrontEndIpProperties {
    IdRef publicIpAddress

    FrontEndIpProperties(String id) {
      publicIpAddress = new IdRef(id)
    }
  }

  static class BackEndAddressPool {
    String name

    BackEndAddressPool() {
      name = "[variables('loadBalancerBackEnd')]"
    }
  }


  static class InboundNatPool {
    String name
    InboundNatPoolProperties properties

    InboundNatPool(AzureInboundPortConfig inboundPortConfig) {
      name = inboundPortConfig.name
      properties = new InboundNatPoolProperties(inboundPortConfig)
    }
  }

  static class InboundNatPoolProperties {
    IdRef frontendIPConfiguration
    String protocol
    int frontendPortRangeStart
    int frontendPortRangeEnd
    int backendPort

    InboundNatPoolProperties(AzureInboundPortConfig inboundPortConfig) {
      frontendIPConfiguration = new IdRef("[variables('frontEndIPConfigID')]")
      protocol = inboundPortConfig.protocol
      frontendPortRangeStart = inboundPortConfig.frontEndPortRangeStart
      frontendPortRangeEnd = inboundPortConfig.frontEndPortRangeEnd
      backendPort = inboundPortConfig.backendPort
    }
  }
}
