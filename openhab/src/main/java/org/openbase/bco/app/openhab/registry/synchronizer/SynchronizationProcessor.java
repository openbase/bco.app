package org.openbase.bco.app.openhab.registry.synchronizer;

/*-
 * #%L
 * BCO Openhab App
 * %%
 * Copyright (C) 2018 openbase.org
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

import org.eclipse.smarthome.core.items.dto.ItemDTO;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.eclipse.smarthome.core.thing.dto.ChannelDTO;
import org.eclipse.smarthome.core.thing.dto.ThingDTO;
import org.eclipse.smarthome.io.rest.core.thing.EnrichedThingDTO;
import org.openbase.bco.app.openhab.OpenHABRestCommunicator;
import org.openbase.bco.registry.remote.Registries;
import org.openbase.jul.exception.CouldNotPerformException;
import org.openbase.jul.exception.NotAvailableException;
import org.openbase.jul.extension.rst.processing.LabelProcessor;
import org.openbase.jul.extension.rst.processing.MetaConfigPool;
import org.openbase.jul.extension.rst.processing.MetaConfigVariableProvider;
import org.openbase.jul.extension.rst.processing.TimestampProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rst.configuration.MetaConfigType.MetaConfig;
import rst.domotic.service.ServiceConfigType.ServiceConfig;
import rst.domotic.service.ServiceTemplateConfigType.ServiceTemplateConfig;
import rst.domotic.service.ServiceTemplateType.ServiceTemplate.ServicePattern;
import rst.domotic.service.ServiceTemplateType.ServiceTemplate.ServiceType;
import rst.domotic.unit.UnitConfigType.UnitConfig;
import rst.domotic.unit.UnitTemplateConfigType.UnitTemplateConfig;
import rst.domotic.unit.UnitTemplateType.UnitTemplate.UnitType;
import rst.domotic.unit.device.DeviceClassType.DeviceClass;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

/**
 * @author <a href="mailto:pleminoq@openbase.org">Tamino Huxohl</a>
 */
public class SynchronizationProcessor {

    public static final String ZWAVE_DEVICE_TYPE_KEY = "zwave_devicetype";

    public static final String OPENHAB_THING_UID_KEY = "OPENHAB_THING_UID";
    public static final String OPENHAB_THING_CLASS_KEY = "OPENHAB_THING_CLASS";
    public static final String OPENHAB_THING_CHANNEL_TYPE_UID_KEY = "OPENHAB_THING_CHANNEL_TYPE_UID";

    private static final Logger LOGGER = LoggerFactory.getLogger(SynchronizationProcessor.class);

    /**
     * Retrieve a device unit config for a thing. This is done by looking for a meta config entry in the device unit
     * config where the id of the thing is referenced.
     *
     * @param thingDTO the thing for which a device should be retrieved
     * @return a device unit config for the thing as described above
     * @throws NotAvailableException if no device could be found
     */
    public static UnitConfig getDeviceForThing(final ThingDTO thingDTO) throws CouldNotPerformException {
        // iterate over all devices
        for (final UnitConfig deviceUnitConfig : Registries.getUnitRegistry().getUnitConfigs(UnitType.DEVICE)) {
            // get the most global meta config
            final MetaConfigPool metaConfigPool = new MetaConfigPool();
            metaConfigPool.register(new MetaConfigVariableProvider("UnitMetaConfig", deviceUnitConfig.getMetaConfig()));

            try {
                // get the value for the thing uid key
                String thingUID = metaConfigPool.getValue(OPENHAB_THING_UID_KEY);
                // if it matches with the uid of the thing return the device
                if (thingUID.equals(thingDTO.UID)) {
                    return deviceUnitConfig;
                }
            } catch (NotAvailableException ex) {
                // value for device not available so continue
            }
        }

        // throw exception because device for thing could not be found
        throw new NotAvailableException("Device for thing[" + thingDTO.UID + "]");
    }

    public static UnitConfig getLocationForThing(final ThingDTO thingDTO) throws CouldNotPerformException, InterruptedException {
        if (thingDTO.location != null) {
            List<UnitConfig> locationConfigs = Registries.getUnitRegistry(true).getUnitConfigsByLabelAndUnitType(thingDTO.location, UnitType.LOCATION);

            if (locationConfigs.size() == 0) {
                throw new NotAvailableException("Location[" + thingDTO.location + "] for thing[" + thingDTO + "]");
            }

            return locationConfigs.get(0);
        }
        throw new NotAvailableException("Location of thing[" + thingDTO + "]");
    }

    public static EnrichedThingDTO getThingForDevice(final UnitConfig deviceUnitConfig) throws NotAvailableException {
        return OpenHABRestCommunicator.getInstance().getThing(getThingIdFromDevice(deviceUnitConfig));
    }

    public static String getThingIdFromDevice(final UnitConfig deviceUnitConfig) throws NotAvailableException {
        final MetaConfigPool metaConfigPool = new MetaConfigPool();
        metaConfigPool.register(new MetaConfigVariableProvider(deviceUnitConfig.getAlias(0) + MetaConfig.class.getSimpleName(), deviceUnitConfig.getMetaConfig()));

        // get the value for the thing uid key
        return metaConfigPool.getValue(OPENHAB_THING_UID_KEY);
    }

    public static EnrichedThingDTO getThingForUnit(final UnitConfig unitConfig) throws NotAvailableException {
        return OpenHABRestCommunicator.getInstance().getThing(getThingIdForUnit(unitConfig));
    }

    public static String getThingIdForUnit(final UnitConfig unitConfig) {
        //TODO: resolving the thing type uid from the unit type
        return new ThingUID("bco", unitConfig.getUnitType().name().toLowerCase(), unitConfig.getId()).toString();
    }


    public static DeviceClass getDeviceClassByThing(final ThingDTO thingDTO) throws CouldNotPerformException {
        String classIdentifier = thingDTO.thingTypeUID;
        if (thingDTO.thingTypeUID.startsWith("zwave")) {
            classIdentifier = ZWAVE_DEVICE_TYPE_KEY + ":" + thingDTO.properties.get(ZWAVE_DEVICE_TYPE_KEY);
        }
        return getDeviceClassByThing(classIdentifier);
    }

    public static DeviceClass getDeviceClassByThing(final String classIdentifier) throws CouldNotPerformException {
        // iterate over all device classes
        for (final DeviceClass deviceClass : Registries.getClassRegistry().getDeviceClasses()) {
            // get the most global meta config
            final MetaConfigPool metaConfigPool = new MetaConfigPool();
            metaConfigPool.register(new MetaConfigVariableProvider("DeviceClassMetaConfig", deviceClass.getMetaConfig()));

            try {
                // get the value for the openHAB thing class key
                String thingUID = metaConfigPool.getValue(OPENHAB_THING_CLASS_KEY);
                // if the uid starts with that return the according device class
                if (classIdentifier.equalsIgnoreCase(thingUID)) {
                    return deviceClass;
                }
            } catch (NotAvailableException ex) {
                // value for device not available so continue
            }
        }
        // throw exception because device class could not be found
        throw new NotAvailableException("DeviceClass for class identifier[" + classIdentifier + "]");

        //TODO: this is a possible fallback solution which is only tested for the fibaro motion sensor: remove or keep?
//        final String[] thingTypeUIDSplit = thingDTO.thingTypeUID.split(":");
//        if (thingTypeUIDSplit.length != 2) {
//            throw new CouldNotPerformException("Could not parse thingTypeUID[" + thingDTO.thingTypeUID + "]. Does not match known pattern binding:deviceInfo");
//        }
//
//        // String binding = split[0];
//        final String deviceInfo = thingTypeUIDSplit[1];
//        final String[] deviceInfoSplit = deviceInfo.split("_");
//        if (deviceInfoSplit.length < 2) {
//            throw new CouldNotPerformException("Could not parse deviceInfo[" + deviceInfo + "]. Does not match known pattern company_productNumber_...");
//        }
//
//        final String company = deviceInfoSplit[0];
//        final String productNumber = deviceInfoSplit[1];
//
//        try {
//            for (final DeviceClass deviceClass : Registries.getDeviceRegistry().getDeviceClasses()) {
//                if (deviceClass.getCompany().equalsIgnoreCase(company)) {
//                    if (deviceClass.getProductNumber().replace("-", "").equalsIgnoreCase(productNumber)) {
//                        return deviceClass;
//                    }
//                }
//            }
//        } catch (InterruptedException ex) {
//            Thread.currentThread().interrupt();
//            throw new CouldNotPerformException("Interrupted", ex);
//        }
//        throw new NotAvailableException("Device from company[" + company + "] with productNumber[" + productNumber + "]");
    }

    public static void registerAndValidateItems(final UnitConfig unitConfig) throws CouldNotPerformException {
        switch (unitConfig.getUnitType()) {
            case DEVICE:
                registerAndValidateItems(unitConfig, getThingForDevice(Registries.getUnitRegistry().getUnitConfigById(unitConfig.getUnitHostId())));
                break;
            default:
                registerAndValidateItems(unitConfig, getThingForUnit(unitConfig));
                break;
        }
    }

    public static Map<ServiceType, ServicePattern> generateServiceMap(final UnitConfig unitConfig) {
        // build service mapping for services to create matching items
        // this map will only contain provider and operation services, and if there are both for the same service the operation service will be saved
        final Map<ServiceType, ServicePattern> serviceTypePatternMap = new HashMap<>();
        for (ServiceConfig serviceConfig : unitConfig.getServiceConfigList()) {
            if (serviceConfig.getServiceDescription().getPattern() == ServicePattern.CONSUMER) {
                continue;
            }

            if (serviceConfig.getServiceDescription().getPattern() == ServicePattern.STREAM) {
                continue;
            }

            if (!serviceTypePatternMap.containsKey(serviceConfig.getServiceDescription().getServiceType())) {
                serviceTypePatternMap.put(serviceConfig.getServiceDescription().getServiceType(), serviceConfig.getServiceDescription().getPattern());
            } else {
                if (serviceTypePatternMap.get(serviceConfig.getServiceDescription().getServiceType()) == ServicePattern.PROVIDER) {
                    if (serviceConfig.getServiceDescription().getPattern() == ServicePattern.OPERATION) {
                        serviceTypePatternMap.put(serviceConfig.getServiceDescription().getServiceType(), serviceConfig.getServiceDescription().getPattern());
                    }
                }
            }
        }
        return serviceTypePatternMap;
    }

    private static String getChannelUID(final UnitConfig unitConfig, final ServiceType serviceType, final ServicePattern servicePattern, final ThingDTO thingDTO) throws CouldNotPerformException {
        switch (unitConfig.getUnitType()) {
            case DEVICE:
                String channelUID = "";
                final UnitConfig deviceUnitConfig = Registries.getUnitRegistry().getUnitConfigById(unitConfig.getUnitHostId());
                final DeviceClass deviceClass = Registries.getClassRegistry().getDeviceClassById(deviceUnitConfig.getDeviceConfig().getDeviceClassId());
                outer:
                for (final UnitTemplateConfig unitTemplateConfig : deviceClass.getUnitTemplateConfigList()) {
                    if (!unitTemplateConfig.getId().equals(unitConfig.getUnitTemplateConfigId())) {
                        continue;
                    }

                    if (OpenHABRestCommunicator.getInstance().hasItem(OpenHABItemProcessor.generateItemName(unitConfig, serviceType))) {
                        // item is already registered
                        continue;
                    }

                    for (final ServiceTemplateConfig serviceTemplateConfig : unitTemplateConfig.getServiceTemplateConfigList()) {
                        if (serviceTemplateConfig.getServiceType() != serviceType) {
                            continue;
                        }

                        final MetaConfigPool metaConfigPool = new MetaConfigPool();
                        metaConfigPool.register(new MetaConfigVariableProvider("ServiceTemplateConfigMetaConfig", serviceTemplateConfig.getMetaConfig()));
                        try {
                            final String channelIdGuess = thingDTO.UID + ":" + metaConfigPool.getValue(OPENHAB_THING_CHANNEL_TYPE_UID_KEY);

                            for (final ChannelDTO channelDTO : thingDTO.channels) {
                                if (channelDTO.uid.equals(channelIdGuess)) {
                                    channelUID = channelDTO.uid;
                                    break outer;
                                }
                            }
                            LOGGER.warn("Could not resolve channel for id [" + channelIdGuess + "]");
                        } catch (NotAvailableException ex) {
                            LOGGER.warn("Service[" + serviceType.name() + "] of unitTemplateConfig[" + unitTemplateConfig.getType().name() +
                                    "] deviceClass[" + LabelProcessor.getBestMatch(deviceClass.getLabel()) + "] handled by openHAB app does have a channel configured");
                        }
                    }
                }

                if (channelUID.isEmpty()) {
                    throw new NotAvailableException("ChannelUID for service[" + serviceType.name() + "] of unit[" + unitConfig.getAlias(0) + "]");
                }
            default:
                return new ChannelUID(new ThingUID(thingDTO.UID), getChannelId(serviceType, servicePattern)).toString();
        }
    }

    private static String getChannelId(final ServiceType serviceType, final ServicePattern servicePattern) {
        return serviceType.name().toLowerCase().replace("_service", "");
    }

    public static void registerAndValidateItems(final UnitConfig unitConfig, final ThingDTO thingDTO) throws CouldNotPerformException {
        for (final Entry<ServiceType, ServicePattern> entry : generateServiceMap(unitConfig).entrySet()) {
            final ServiceType serviceType = entry.getKey();
            final ServicePattern servicePattern = entry.getValue();

            LOGGER.debug("Register/Validate item for service[" + serviceType.name() + "] of unit[" + unitConfig.getAlias(0) + "]");

            // This is kind of a hack to support switching only lights in a location
            if (unitConfig.getUnitType() == UnitType.LOCATION && serviceType == ServiceType.POWER_STATE_SERVICE) {
                final String itemName = OpenHABItemProcessor.generateItemName(unitConfig, serviceType) + "Light";

                LOGGER.debug("Generate special entry to only switch lights in a location: " + itemName);
                if (!OpenHABRestCommunicator.getInstance().hasItem(itemName)) {
                    final String channelUID = getChannelUID(unitConfig, serviceType, servicePattern, thingDTO) + "_light";

                    LOGGER.debug("Item does not already exist: " + channelUID);
                    // create and register item
                    ItemDTO itemDTO = new ItemDTO();
                    itemDTO.label = generateItemLabel(unitConfig, serviceType) + " Light";
                    itemDTO.name = itemName;
                    try {
                        itemDTO.type = OpenHABItemProcessor.getItemType(serviceType, servicePattern);
                    } catch (NotAvailableException ex) {
                        LOGGER.warn("Skip service[" + serviceType.name() + "] of unit[" + LabelProcessor.getBestMatch(unitConfig.getLabel()) + "] because no item type available");
                        continue;
                    }
                    itemDTO = OpenHABRestCommunicator.getInstance().registerItem(itemDTO);
                    LOGGER.debug("Successfully registered item[" + itemDTO.name + "] for dal unit");

                    // link item to thing channel
                    OpenHABRestCommunicator.getInstance().registerItemChannelLink(itemDTO.name, channelUID);
                    LOGGER.debug("Successfully created link between item[" + itemDTO.name + "] and channel[" + channelUID + "]");
                }
            }

            if (OpenHABRestCommunicator.getInstance().hasItem(OpenHABItemProcessor.generateItemName(unitConfig, serviceType))) {
                // item is already registered
                LOGGER.debug("Skip because already available");
                continue;
            }

            String channelUID;
            try {
                channelUID = getChannelUID(unitConfig, serviceType, servicePattern, thingDTO);
            } catch (NotAvailableException ex) {
                LOGGER.warn("Skip service[" + serviceType.name() + ", " + servicePattern.name() + "] of unit[" + LabelProcessor.getBestMatch(unitConfig.getLabel()) + "] because no channel is available ");
                continue;
            }

            // create and register item
            ItemDTO itemDTO = new ItemDTO();
            itemDTO.label = generateItemLabel(unitConfig, serviceType);
            itemDTO.name = OpenHABItemProcessor.generateItemName(unitConfig, serviceType);
            try {
                itemDTO.type = OpenHABItemProcessor.getItemType(serviceType, servicePattern);
            } catch (NotAvailableException ex) {
                LOGGER.warn("Skip service[" + serviceType.name() + "] of unit[" + LabelProcessor.getBestMatch(unitConfig.getLabel()) + "] because no item type available");
                continue;
            }
            itemDTO = OpenHABRestCommunicator.getInstance().registerItem(itemDTO);
            LOGGER.debug("Successfully registered item[" + itemDTO.name + "] for dal unit");

            // link item to thing channel
            OpenHABRestCommunicator.getInstance().registerItemChannelLink(itemDTO.name, channelUID);
            LOGGER.debug("Successfully created link between item[" + itemDTO.name + "] and channel[" + channelUID + "]");
        }
    }

    public static final String generateItemLabel(final UnitConfig unitConfig, final ServiceType serviceType) throws CouldNotPerformException {
        return LabelProcessor.getFirstLabel(unitConfig.getLabel()) + " " + LabelProcessor.getBestMatch(Registries.getTemplateRegistry().getServiceTemplateByType(serviceType).getLabel());
    }

    /**
     * Update the thing label and location of a thing according to a device.
     *
     * @param deviceUnitConfig the device from which the label and location is taken
     * @param thing            the thing which is updated
     * @return if the thing has been updated meaning that the label or location changed
     * @throws CouldNotPerformException if the update could not be performed
     */
    public static boolean updateThingToUnit(final UnitConfig deviceUnitConfig, final EnrichedThingDTO thing) throws CouldNotPerformException {
        boolean modification = false;
        final String label = LabelProcessor.getBestMatch(deviceUnitConfig.getLabel());
        if (thing.label == null || !thing.label.equals(label)) {
            thing.label = label;
            modification = true;
        }

        final UnitConfig locationUnitConfig = Registries.getUnitRegistry().getUnitConfigById(deviceUnitConfig.getPlacementConfig().getLocationId());
        final String locationLabel = LabelProcessor.getBestMatch(locationUnitConfig.getLabel());
        if (thing.location == null || !thing.location.equals(locationLabel)) {
            thing.location = locationLabel;
            modification = true;
        }

        return modification;
    }

    /**
     * Update the unit label and location according to a changed thing.
     *
     * @param thing      the thing which has changed
     * @param unitConfig the unit which is updated
     * @return if the thing has been updated meaning that the label or location changed
     * @throws CouldNotPerformException if the update could not be performed
     */
    public static boolean updateUnitToThing(final EnrichedThingDTO thing, final UnitConfig.Builder unitConfig) throws CouldNotPerformException, InterruptedException {
        boolean modification = false;
        // TODO: only adding the label, or remove the old one, or at least move new one to higher priority?
        // update label and location
        if (!LabelProcessor.contains(unitConfig.getLabel(), thing.label)) {
            modification = true;
            LabelProcessor.addLabel(unitConfig.getLabelBuilder(), Locale.ENGLISH, thing.label);
        }
        if (thing.location != null) {
            final String locationId = SynchronizationProcessor.getLocationForThing(thing).getId();

            if (!locationId.equalsIgnoreCase(unitConfig.getPlacementConfig().getLocationId())) {
                modification = true;
                unitConfig.getPlacementConfigBuilder().setLocationId(locationId);
                if (unitConfig.getUnitType() == UnitType.DEVICE) {
                    unitConfig.getDeviceConfigBuilder().getInventoryStateBuilder().setLocationId(locationId).setTimestamp(TimestampProcessor.getCurrentTimestamp());
                }
            }
        }

        return modification;
    }
}