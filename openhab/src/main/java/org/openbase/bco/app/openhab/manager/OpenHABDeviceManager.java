package org.openbase.bco.app.openhab.manager;

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

import org.openbase.bco.app.openhab.OpenHABRestCommunicator;
import org.openbase.bco.app.openhab.manager.service.OpenHABServiceFactory;
import org.openbase.bco.dal.lib.layer.unit.UnitController;
import org.openbase.bco.manager.device.core.DeviceManagerController;
import org.openbase.bco.manager.device.lib.DeviceController;
import org.openbase.bco.registry.remote.Registries;
import org.openbase.jul.exception.CouldNotPerformException;
import org.openbase.jul.exception.InitializationException;
import org.openbase.jul.exception.InstantiationException;
import org.openbase.jul.iface.Launchable;
import org.openbase.jul.iface.VoidInitializable;
import org.openbase.jul.pattern.Observable;
import org.openbase.jul.pattern.Observer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rst.domotic.unit.UnitConfigType.UnitConfig;
import rst.domotic.unit.device.DeviceClassType.DeviceClass;

import java.util.Map;
import java.util.Map.Entry;

public class OpenHABDeviceManager implements Launchable<Void>, VoidInitializable {

    public static final String ITEM_STATE_TOPIC_FILTER = "smarthome/items/(.+)/state";

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenHABDeviceManager.class);

    private final DeviceManagerController deviceManagerController;
    private final CommandExecutor commandExecutor;
    private final Observer synchronizationObserver;

    public OpenHABDeviceManager() throws InterruptedException, InstantiationException {
        this.deviceManagerController = new DeviceManagerController(new OpenHABServiceFactory()) {

            @Override
            public boolean isSupported(UnitConfig config) throws CouldNotPerformException {
                DeviceClass deviceClass = Registries.getClassRegistry().getDeviceClassById(config.getDeviceConfig().getDeviceClassId());
                if (!deviceClass.getBindingConfig().getBindingId().equals("OPENHAB")) {
                    return false;
                }

                return super.isSupported(config);
            }
        };
        this.commandExecutor = new CommandExecutor(deviceManagerController.getUnitControllerRegistry());
        this.synchronizationObserver = ((observable, value) -> {
            for (final Entry<String, String> entry : OpenHABRestCommunicator.getInstance().getStates().entrySet()) {
                try {
                    commandExecutor.applyStateUpdate(entry.getKey(), entry.getValue());
                } catch (CouldNotPerformException ex) {
                    LOGGER.warn("Skip synchronization of item[" + entry.getKey() + "] state[" + entry.getValue() + "] because unit not available", ex);
                }
            }
        });
    }

    @Override
    public void init() throws InterruptedException, InitializationException {
        deviceManagerController.init();
    }

    @Override
    public void activate() throws CouldNotPerformException, InterruptedException {
        deviceManagerController.getUnitControllerRegistry().addObserver(synchronizationObserver);
        deviceManagerController.activate();
        OpenHABRestCommunicator.getInstance().addSSEObserver(commandExecutor, ITEM_STATE_TOPIC_FILTER);
    }

    @Override
    public void deactivate() throws CouldNotPerformException, InterruptedException {
        deviceManagerController.getUnitControllerRegistry().removeObserver(synchronizationObserver);
        OpenHABRestCommunicator.getInstance().removeSSEObserver(commandExecutor, ITEM_STATE_TOPIC_FILTER);
        deviceManagerController.deactivate();
    }

    @Override
    public boolean isActive() {
        return deviceManagerController.isActive();
    }
}
