package org.openbase.bco.app.preset;

/*
 * #%L
 * BCO App Preset
 * %%
 * Copyright (C) 2018 - 2019 openbase.org
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

import org.openbase.bco.dal.lib.layer.unit.Unit;
import org.openbase.bco.dal.remote.action.RemoteAction;
import org.openbase.bco.dal.remote.layer.unit.Units;
import org.openbase.bco.dal.remote.layer.unit.location.LocationRemote;
import org.openbase.bco.dal.control.layer.unit.app.AbstractAppController;
import org.openbase.bco.registry.remote.Registries;
import org.openbase.jul.exception.CouldNotPerformException;
import org.openbase.jul.exception.InstantiationException;
import org.openbase.jul.exception.NotAvailableException;
import org.openbase.jul.exception.ShutdownInProgressException;
import org.openbase.jul.exception.printer.ExceptionPrinter;
import org.openbase.jul.pattern.Observer;
import org.openbase.jul.schedule.RecurrenceEventFilter;
import org.openbase.jul.schedule.SyncObject;
import org.openbase.jul.schedule.Timeout;
import org.openbase.type.domotic.action.ActionDescriptionType.ActionDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.openbase.type.domotic.service.ServiceTemplateType.ServiceTemplate.ServiceType;
import org.openbase.type.domotic.state.ActivationStateType.ActivationState;
import org.openbase.type.domotic.state.PowerStateType.PowerState.State;
import org.openbase.type.domotic.state.PresenceStateType.PresenceState;
import org.openbase.type.domotic.unit.UnitConfigType.UnitConfig;
import org.openbase.type.domotic.unit.UnitTemplateType.UnitTemplate.UnitType;
import org.openbase.type.domotic.unit.location.LocationConfigType.LocationConfig.LocationType;
import org.openbase.type.vision.HSBColorType.HSBColor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * UnitConfig
 */
public class NightLightApp extends AbstractAppController {

    public static final HSBColor COLOR_ORANGE = HSBColor.newBuilder().setHue(30d).setSaturation(1.0d).setBrightness(0.20d).build();
    private static final Logger LOGGER = LoggerFactory.getLogger(NightLightApp.class);
    private static final String META_CONFIG_KEY_EXCLUDE_LOCATION = "EXCLUDE_LOCATION";

    private SyncObject locationMapLock = new SyncObject("LocationMapLock");

    private Map<Unit, RemoteAction> presentsActionLocationMap, absenceActionLocationMap;
    private Map<LocationRemote, Observer> locationMap;

    public NightLightApp() throws InstantiationException {
        this.locationMap = new HashMap<>();
        this.presentsActionLocationMap = new HashMap<>();
        this.absenceActionLocationMap = new HashMap<>();
    }

    private void update(final LocationRemote location, final Timeout timeout) {
        try {
            // init present state with main location.
            PresenceState.State presentState = location.getPresenceState().getValue();
            for (final LocationRemote neighbor : location.getNeighborLocationList(true)) {

                // break if any present person is detected.
                if (presentState == PresenceState.State.PRESENT) {
                    break;
                }

                // if not unknown apply state of neighbor
                if (neighbor.getPresenceState().getValue() != PresenceState.State.UNKNOWN) {
                    presentState = neighbor.getPresenceState().getValue();
                }
            }

            final RemoteAction absenceAction = absenceActionLocationMap.get(location);
            final RemoteAction presentsAction = presentsActionLocationMap.get(location);

            switch (presentState) {
                case PRESENT:
                    if (timeout != null) {
                        timeout.restart();
                    }

                    // if ongoing action skip the update.
                    if (presentsAction != null && !presentsAction.isDone()) {
                        return;
                    }

                    // System.out.println("Nightmode: switch location " + location.getLabel() + " to orange because of present state].");
                    presentsActionLocationMap.put(location, observe(location.setColor(COLOR_ORANGE, getDefaultActionParameter())));

                    // cancel absence actions
                    if (absenceAction != null) {
                        absenceAction.cancel();
                        absenceActionLocationMap.remove(location);
                    }

                    break;
                case ABSENT:

                    // if ongoing action skip the update.
                    if (absenceAction != null && !absenceAction.isDone()) {
                        return;
                    }

                    if (timeout == null || timeout.isExpired() || !timeout.isActive()) {
                        // System.out.println("Location power State[" + location.getPowerState().getValue() + ". " + location.getPowerState(UnitType.LIGHT).getValue() + "]");
                        // System.out.println("Nightmode: switch off " + location.getLabel() + " because of absent state.");
                        absenceActionLocationMap.put(location, observe(location.setPowerState(State.OFF, UnitType.LIGHT, getDefaultActionParameter())));
                    }

                    // cancel presents actions
                    if (presentsAction != null) {
                        presentsAction.cancel();
                        presentsActionLocationMap.remove(location);
                    }
                    break;
            }
        } catch (ShutdownInProgressException ex) {
            // skip update when shutdown was initiated.
        } catch (CouldNotPerformException ex) {
            ExceptionPrinter.printHistory("Could not control light in " + location.getLabel("?") + " by night light!", ex, LOGGER);
        }
    }

    private static boolean isColorReached(final HSBColor hsbColorA, final HSBColor hsbColorB) {
        return withinMargin(hsbColorA.getHue(), hsbColorB.getHue(), 10d)
                && withinMargin(hsbColorA.getSaturation(), hsbColorB.getSaturation(), 0.05d)
                && withinMargin(hsbColorA.getBrightness(), hsbColorB.getBrightness(), 0.05d);
    }

    private static boolean withinMargin(double a, double b, double margin) {
        return Math.abs(a - b) < margin;
    }

    @Override
    public UnitConfig applyConfigUpdate(UnitConfig config) throws CouldNotPerformException, InterruptedException {
        final UnitConfig unitConfig = super.applyConfigUpdate(config);
        updateLocationMap();
        return unitConfig;
    }

    private void updateLocationMap() throws CouldNotPerformException {
        try {
            synchronized (locationMapLock) {

                // deregister all tile remotes
                locationMap.forEach((remote, observer) -> {
                    remote.removeDataObserver(observer);
                });

                // clear all tile remotes
                locationMap.clear();

                final Collection<String> excludedLocations = new ArrayList<>();

                // check location exclusion
                try {
                    excludedLocations.addAll(generateVariablePool().getValues(META_CONFIG_KEY_EXCLUDE_LOCATION).values());
                } catch (final NotAvailableException ex) {
                    ExceptionPrinter.printHistory("Could not load variable pool!", ex, LOGGER);
                    // no locations excluded.
                }

                // load parent location
                final UnitConfig parentLocationConfig = Registries.getUnitRegistry().getUnitConfigById(getConfig().getPlacementConfig().getLocationId());

                // load tile remotes
                remoteLocationLoop:
                for (String s : parentLocationConfig.getLocationConfig().getChildIdList()) {

                    final UnitConfig locationUnitConfig = Registries.getUnitRegistry().getUnitConfigById(s);

                    // let only tiles pass
                    if (locationUnitConfig.getLocationConfig().getLocationType() != LocationType.TILE) {
                        continue;
                    }

                    // check if location was excluded by id
                    if (excludedLocations.contains(locationUnitConfig.getId())) {
                        // System.out.println("exclude locations: " + locationUnitConfig.getLabel());
                        continue remoteLocationLoop;
                    }

                    // check if location was excluded by alias
                    for (String alias : locationUnitConfig.getAliasList()) {
                        if (excludedLocations.contains(alias)) {
                            // System.out.println("exclude locations: " + locationUnitConfig.getLabel());
                            continue remoteLocationLoop;
                        }
                    }

                    final LocationRemote remote = Units.getUnit(locationUnitConfig, false, Units.LOCATION);

                    // skip locations without colorable lights.
                    if (!remote.isServiceAvailable(ServiceType.COLOR_STATE_SERVICE)) {
                        continue remoteLocationLoop;
                    }

                    final Timeout nightModeTimeout = new Timeout(10, TimeUnit.MINUTES) {
                        @Override
                        public void expired() throws InterruptedException {
                            update(remote, this);
                        }
                    };

                    locationMap.put(remote, (source, data) -> update(remote, nightModeTimeout));
                }

                if (getActivationState().getValue() == ActivationState.State.ACTIVE) {
                    locationMap.forEach((remote, observer) -> {
                        remote.addDataObserver(observer);
                        update(remote, null);
                    });
                }
            }
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (final CouldNotPerformException ex) {
            throw new CouldNotPerformException("Could not update location map", ex);
        }
    }

    @Override
    public void shutdown() {
        super.shutdown();
        synchronized (locationMapLock) {
            locationMap.clear();
        }
    }

    @Override
    protected ActionDescription execute(final ActivationState activationState) throws CouldNotPerformException, InterruptedException {
        synchronized (locationMapLock) {
            locationMap.forEach((remote, observer) -> {
                remote.addDataObserver(observer);
                try {
                    for (LocationRemote neighbor : remote.getNeighborLocationList(false)) {
                        neighbor.addDataObserver(observer);
                    }
                } catch (CouldNotPerformException ex) {
                    ExceptionPrinter.printHistory("Could not register observer on neighbor locations.", ex, LOGGER);
                }
                update(remote, null);
            });
        }
        return activationState.getResponsibleAction();
    }

    @Override
    protected void stop(final ActivationState activationState) throws InterruptedException {
        synchronized (locationMapLock) {

            // remove observer
            locationMap.forEach((remote, observer) -> {
                remote.removeDataObserver(observer);
                try {
                    for (LocationRemote neighbor : remote.getNeighborLocationList(false)) {
                        neighbor.removeDataObserver(observer);
                    }
                } catch (CouldNotPerformException ex) {
                    ExceptionPrinter.printHistory("Could not remove observer from neighbor locations.", ex, LOGGER);
                }
            });

            final ArrayList<Future<ActionDescription>> cancelTaskList = new ArrayList<>();
            for (Entry<Unit, RemoteAction> unitActionEntry : presentsActionLocationMap.entrySet()) {
                cancelTaskList.add(unitActionEntry.getValue().cancel());
            }
            for (Entry<Unit, RemoteAction> unitActionEntry : absenceActionLocationMap.entrySet()) {
                cancelTaskList.add(unitActionEntry.getValue().cancel());
            }

            for (Future<ActionDescription> cancelTask : cancelTaskList) {
                try {
                    cancelTask.get(10, TimeUnit.SECONDS);
                } catch (ExecutionException | TimeoutException ex) {
                    ExceptionPrinter.printHistory("Could not cancel action!", ex, logger);
                }
            }

            // clear actions list
            presentsActionLocationMap.clear();
            absenceActionLocationMap.clear();
        }
    }
}
