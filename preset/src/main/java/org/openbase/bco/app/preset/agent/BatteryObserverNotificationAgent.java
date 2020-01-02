package org.openbase.bco.app.preset.agent;

/*
 * #%L
 * BCO App Preset
 * %%
 * Copyright (C) 2018 - 2020 openbase.org
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

import com.google.protobuf.ByteString;
import com.google.protobuf.ProtocolStringList;
import org.openbase.bco.app.preset.agent.AbstractDelayedTriggerableAgent.DelayMode;
import org.openbase.bco.dal.control.layer.unit.agent.AbstractAgentController;
import org.openbase.bco.dal.remote.action.RemoteAction;
import org.openbase.bco.dal.remote.layer.unit.Units;
import org.openbase.bco.dal.remote.layer.unit.location.LocationRemote;
import org.openbase.bco.dal.remote.trigger.GenericBoundedDoubleValueTrigger;
import org.openbase.bco.dal.remote.trigger.GenericBoundedDoubleValueTrigger.TriggerOperation;
import org.openbase.bco.dal.remote.trigger.GenericServiceStateValueTrigger;
import org.openbase.bco.registry.remote.Registries;
import org.openbase.jul.exception.CouldNotPerformException;
import org.openbase.jul.exception.InitializationException;
import org.openbase.jul.exception.InstantiationException;
import org.openbase.jul.exception.NotAvailableException;
import org.openbase.jul.pattern.trigger.TriggerPool.TriggerAggregation;
import org.openbase.type.domotic.action.ActionDescriptionType.ActionDescription;
import org.openbase.type.domotic.service.ServiceTemplateType.ServiceTemplate.ServiceType;
import org.openbase.type.domotic.state.ActivationStateType.ActivationState;
import org.openbase.type.domotic.state.PowerStateType.PowerState.State;
import org.openbase.type.domotic.state.PresenceStateType.PresenceState;
import org.openbase.type.domotic.unit.UnitConfigType;
import org.openbase.type.domotic.unit.UnitConfigType.UnitConfig;
import org.openbase.type.domotic.unit.UnitTemplateType.UnitTemplate.UnitType;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * @author <a href="mailto:tmichalski@techfak.uni-bielefeld.de">Timo Michalski</a>
 */
public class BatteryObserverNotificationAgent extends AbstractAgentController {

    private LocationRemote locationRemote;
    private RemoteAction lastAction;

    public BatteryObserverNotificationAgent() throws InstantiationException {
        super();
        UnitConfig unitConfig;
    }

    @Override
    public void init(final UnitConfig config) throws InitializationException, InterruptedException {
        super.init(config);
        try {
            locationRemote = Units.getUnit(getConfig().getPlacementConfig().getLocationId(), false, Units.LOCATION);
        } catch (CouldNotPerformException ex) {
            throw new InitializationException(this, ex);
        }
    }

    @Override
    protected ActionDescription execute(ActivationState activationState) throws CouldNotPerformException, InterruptedException {
        return activationState.getResponsibleAction();
    }
}
