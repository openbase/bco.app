package org.openbase.bco.app.cloud.connector.mapping.service;

/*-
 * #%L
 * BCO Cloud Connector
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

import com.google.gson.JsonObject;
import org.openbase.bco.app.cloud.connector.mapping.lib.Command;
import org.openbase.bco.app.cloud.connector.mapping.unit.TemperatureControllerUnitTypeMapper;
import org.openbase.jul.exception.CouldNotPerformException;
import rst.domotic.service.ServiceTemplateType.ServiceTemplate.ServiceType;
import rst.domotic.state.TemperatureStateType.TemperatureState;

/**
 * @author <a href="mailto:pleminoq@openbase.org">Tamino Huxohl</a>
 */
public class TemperatureSettingTraitMapper extends AbstractTraitMapper<TemperatureState> {

    public static final String TEMPERATURE_SETPOINT_KEY = "thermostatTemperatureSetpoint";

    public TemperatureSettingTraitMapper() {
        super(ServiceType.TARGET_TEMPERATURE_STATE_SERVICE);
    }

    @Override
    public TemperatureState map(JsonObject jsonObject, Command command) throws CouldNotPerformException {
        switch (command) {
            case THERMOSTAT_TEMPERATURE_SETPOINT:
                if (!jsonObject.has(TEMPERATURE_SETPOINT_KEY)) {
                    throw new CouldNotPerformException("Could not map from jsonObject[" + jsonObject.toString() + "] to ["
                            + TemperatureState.class.getSimpleName() + "]. Attribute[" + TEMPERATURE_SETPOINT_KEY + "] is missing");
                }

                try {
                    final float temperature = jsonObject.get(TEMPERATURE_SETPOINT_KEY).getAsFloat();
                    return TemperatureState.newBuilder().setTemperature(temperature).build();
                } catch (ClassCastException | IllegalStateException ex) {
                    // thrown if it is not a boolean
                    throw new CouldNotPerformException("Could not map from jsonObject[" + jsonObject.toString() + "] to ["
                            + TemperatureState.class.getSimpleName() + "]. Attribute[" + TEMPERATURE_SETPOINT_KEY + "] is not a float");
                }
            default:
                throw new CouldNotPerformException("Command[" + command.name() + "] not yet supported by " + getClass().getSimpleName());
        }
    }

    @Override
    protected TemperatureState map(JsonObject jsonObject) throws CouldNotPerformException {
        throw new CouldNotPerformException("Use method with command parameter");
    }

    @Override
    public void map(TemperatureState temperatureState, JsonObject jsonObject) throws CouldNotPerformException {
        throw new CouldNotPerformException("Operation not supported, should be handled by " +
                TemperatureControllerUnitTypeMapper.class.getSimpleName());
    }
}
