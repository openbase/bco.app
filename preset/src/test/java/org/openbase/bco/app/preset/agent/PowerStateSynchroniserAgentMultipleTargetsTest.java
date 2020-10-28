package org.openbase.bco.app.preset.agent;

/*-
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

import org.openbase.app.test.agent.AbstractBCOAgentManagerTest;
import org.openbase.bco.registry.mock.MockRegistry;
import org.openbase.bco.registry.remote.Registries;
import org.openbase.jul.exception.CouldNotPerformException;
import org.openbase.type.configuration.EntryType;
import org.openbase.type.configuration.MetaConfigType;
import org.openbase.type.domotic.state.EnablingStateType;
import org.openbase.type.domotic.unit.UnitConfigType;
import org.openbase.type.domotic.unit.UnitTemplateType;
import org.slf4j.LoggerFactory;

public class PowerStateSynchroniserAgentMultipleTargetsTest extends AbstractBCOAgentManagerTest {

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(PowerStateSynchroniserAgentSingleTargetTest.class);

    private static final String AGENT_ALIAS = "Power_State_Sync_Agent_Unit_Test";
    private static final long STATE_AWAIT_TIMEOUT = 1000;

    public PowerStateSynchroniserAgentMultipleTargetsTest() {
    }

    private String sourceId;
    private String targetId1;
    private String targetId2;

    @Override
    public UnitConfigType.UnitConfig getAgentConfig() throws CouldNotPerformException {
        final UnitConfigType.UnitConfig.Builder agentUnitConfig = MockRegistry.generateAgentConfig(MockRegistry.LABEL_AGENT_CLASS_POWER_STATE_SYNCHRONISER, AGENT_ALIAS, MockRegistry.ALIAS_LOCATION_ROOT_PARADISE);

        // generate meta config
        final MetaConfigType.MetaConfig.Builder metaConfig = agentUnitConfig.getMetaConfigBuilder();
        EntryType.Entry.Builder source = metaConfig.addEntryBuilder().setKey(PowerStateSynchroniserAgent.SOURCE_KEY);
        EntryType.Entry.Builder target1 = metaConfig.addEntryBuilder().setKey(PowerStateSynchroniserAgent.TARGET_KEY + "_1");
        EntryType.Entry.Builder target2 = metaConfig.addEntryBuilder().setKey(PowerStateSynchroniserAgent.TARGET_KEY + "_2");
        for (UnitConfigType.UnitConfig unit : Registries.getUnitRegistry().getDalUnitConfigs()) {
            if (unit.getEnablingState().getValue() != EnablingStateType.EnablingState.State.ENABLED) {
                continue;
            }

            if (unit.getUnitType() == UnitTemplateType.UnitTemplate.UnitType.DIMMER && source.getValue().isEmpty()) {
                sourceId = unit.getId();
                source.setValue(unit.getId());
            } else if (unit.getUnitType() == UnitTemplateType.UnitTemplate.UnitType.COLORABLE_LIGHT && target1.getValue().isEmpty()) {
                targetId1 = unit.getId();
                target1.setValue(unit.getId());
            } else if (unit.getUnitType() == UnitTemplateType.UnitTemplate.UnitType.POWER_SWITCH && target2.getValue().isEmpty()) {
                targetId2 = unit.getId();
                target2.setValue(unit.getId());
            }

            if (source.hasValue() && target1.hasValue() && target2.hasValue()) {
                break;
            }
        }

        return agentUnitConfig.build();
    }
}
