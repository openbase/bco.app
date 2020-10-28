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

import org.junit.Test;
import org.openbase.app.test.agent.AbstractBCOAgentManagerTest;
import org.openbase.bco.dal.lib.state.States;
import org.openbase.bco.dal.remote.action.RemoteAction;
import org.openbase.bco.dal.remote.layer.unit.ColorableLightRemote;
import org.openbase.bco.dal.remote.layer.unit.PowerSwitchRemote;
import org.openbase.bco.dal.remote.layer.unit.Units;
import org.openbase.bco.dal.remote.layer.unit.util.UnitStateAwaiter;
import org.openbase.bco.registry.mock.MockRegistry;
import org.openbase.bco.registry.remote.Registries;
import org.openbase.jul.exception.CouldNotPerformException;
import org.openbase.jul.exception.NotAvailableException;
import org.openbase.type.configuration.EntryType.Entry;
import org.openbase.type.configuration.MetaConfigType.MetaConfig;
import org.openbase.type.domotic.action.ActionDescriptionType.ActionDescription;
import org.openbase.type.domotic.state.EnablingStateType.EnablingState;
import org.openbase.type.domotic.state.PowerStateType.PowerState;
import org.openbase.type.domotic.state.PowerStateType.PowerState.State;
import org.openbase.type.domotic.unit.UnitConfigType.UnitConfig;
import org.openbase.type.domotic.unit.UnitTemplateType.UnitTemplate.UnitType;
import org.openbase.type.domotic.unit.dal.PowerSwitchDataType.PowerSwitchData;
import org.openbase.type.vision.HSBColorType;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * * @author <a href="mailto:pleminoq@openbase.org">Tamino Huxohl</a>
 */
public class PowerStateSynchroniserAgentSingleTargetTest extends AbstractBCOAgentManagerTest {

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(PowerStateSynchroniserAgentSingleTargetTest.class);

    private static final String AGENT_ALIAS = "Power_State_Sync_Agent_Unit_Test";
    private static final long STATE_AWAIT_TIMEOUT = 1000;

    public PowerStateSynchroniserAgentSingleTargetTest() {
    }

    private String sourceId;
    private String targetId;

    private PowerSwitchRemote sourceRemote;
    private ColorableLightRemote targetRemote;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        sourceRemote = Units.getUnit(sourceId, false, PowerSwitchRemote.class);
        targetRemote = Units.getUnit(targetId, false, ColorableLightRemote.class);
    }

    /**
     * Validate that the source action:
     * <ol>
     *     <li>was caused be the user belonging to the power state synchroniser agent</li>
     *     <li>has a cause</li>
     *     <li>the cause is one of the target actions</li>
     *     <li>has the same priority as the cause</li>
     *     <li>has the same execution time period as the cause</li>
     * </ol>
     *
     * @param sourceAction  the action to be validated
     * @param targetActions all target actions which could have caused the source action
     * @throws NotAvailableException if the action id of one of the target actions is not available
     */
    private void validateSourceAction(final ActionDescription sourceAction, final RemoteAction... targetActions) throws NotAvailableException {
        assertEquals(agentUser.getId(), sourceAction.getActionInitiator().getInitiatorId());
        assertTrue("Source action does not have a cause", sourceAction.getActionCauseCount() > 0);

        boolean causedByTargetAction = false;
        for (RemoteAction targetAction : targetActions) {
            causedByTargetAction = causedByTargetAction || targetAction.getActionId().equals(sourceAction.getActionCause(0).getActionId());
        }
        assertTrue("None of the expected target action caused the source action", causedByTargetAction);
        assertEquals(sourceAction.getActionCause(0).getPriority(), sourceAction.getPriority());
        assertEquals(sourceAction.getActionCause(0).getExecutionTimePeriod(), sourceAction.getExecutionTimePeriod());

    }

    /**
     * Test if the power state synchroniser controls the source as expected
     * if the targets power state changes.
     *
     * @throws java.lang.Exception
     */
    @Test(timeout = 15000)
    public void testSetTargetPowerState() throws Exception {
        final UnitStateAwaiter<PowerSwitchData, PowerSwitchRemote> sourceRemoteStateAwaiter = new UnitStateAwaiter<>(sourceRemote);
        RemoteAction targetRemoteAction;

        // Test turning target off
        targetRemoteAction = waitForExecution(targetRemote.setPowerState(State.OFF));
        assertEquals("Target has not turned off", State.OFF, targetRemote.getPowerState().getValue());
        sourceRemoteStateAwaiter.waitForState(data -> data.getPowerState().getValue() == State.OFF);
        assertEquals("Source has not turned off", State.OFF, sourceRemote.getPowerState().getValue());
        validateSourceAction(sourceRemote.getActionList().get(0), targetRemoteAction);

        // Test turning target on
        targetRemoteAction = waitForExecution(targetRemote.setPowerState(State.ON));
        assertEquals("Target has not turned on", State.ON, targetRemote.getPowerState().getValue());
        sourceRemoteStateAwaiter.waitForState(data -> data.getPowerState().getValue() == PowerState.State.ON);
        assertEquals("Source has not turned on", State.ON, sourceRemote.getPowerState().getValue());
        validateSourceAction(sourceRemote.getActionList().get(0), targetRemoteAction);
    }

    /**
     * Test if the power state synchroniser controls the source as expected
     * if the targets brightness state changes.
     *
     * @throws java.lang.Exception
     */
    @Test(timeout = 15000)
    public void testSetTargetBrightnessState() throws Exception {
        final UnitStateAwaiter<PowerSwitchData, PowerSwitchRemote> sourceRemoteStateAwaiter = new UnitStateAwaiter<>(sourceRemote);
        RemoteAction targetRemoteAction;

        // Test turning target off
        targetRemoteAction = waitForExecution(targetRemote.setBrightness(0));
        assertEquals("Target has not turned off", State.OFF, targetRemote.getPowerState().getValue());
        sourceRemoteStateAwaiter.waitForState(data -> data.getPowerState().getValue() == State.OFF);
        assertEquals("Source has not turned off", State.OFF, sourceRemote.getPowerState().getValue());
        validateSourceAction(sourceRemote.getActionList().get(0), targetRemoteAction);

        // Test turning target on
        targetRemoteAction = waitForExecution(targetRemote.setBrightness(1.0));
        assertEquals("Target has not turned on", State.ON, targetRemote.getPowerState().getValue());
        sourceRemoteStateAwaiter.waitForState(data -> data.getPowerState().getValue() == PowerState.State.ON);
        assertEquals("Source has not turned on", State.ON, sourceRemote.getPowerState().getValue());
        validateSourceAction(sourceRemote.getActionList().get(0), targetRemoteAction);
    }

    /**
     * Test if the power state synchroniser controls the source as expected
     * if the targets color state changes.
     *
     * @throws java.lang.Exception
     */
    @Test(timeout = 15000)
    public void testSetTargetColorState() throws Exception {
        final UnitStateAwaiter<PowerSwitchData, PowerSwitchRemote> sourceRemoteStateAwaiter = new UnitStateAwaiter<>(sourceRemote);
        RemoteAction targetRemoteAction;

        // Test turning target off
        targetRemoteAction = waitForExecution(targetRemote.setColorState(States.Color.BLACK));
        assertEquals("Target has not turned off", State.OFF, targetRemote.getPowerState().getValue());
        sourceRemoteStateAwaiter.waitForState(data -> data.getPowerState().getValue() == State.OFF);
        assertEquals("Source has not turned off", State.OFF, sourceRemote.getPowerState().getValue());
        validateSourceAction(sourceRemote.getActionList().get(0), targetRemoteAction);

        // Test turning target on
        targetRemoteAction = waitForExecution(targetRemote.setColorState(States.Color.RED));
        assertEquals("Target has not turned on", State.ON, targetRemote.getPowerState().getValue());
        sourceRemoteStateAwaiter.waitForState(data -> data.getPowerState().getValue() == PowerState.State.ON);
        assertEquals("Source has not turned on", State.ON, sourceRemote.getPowerState().getValue());
        validateSourceAction(sourceRemote.getActionList().get(0), targetRemoteAction);
    }

    @Override
    public UnitConfig getAgentConfig() throws CouldNotPerformException {
        final UnitConfig.Builder agentUnitConfig = MockRegistry.generateAgentConfig(MockRegistry.LABEL_AGENT_CLASS_POWER_STATE_SYNCHRONISER, AGENT_ALIAS, MockRegistry.ALIAS_LOCATION_ROOT_PARADISE);

        // generate meta config
        final MetaConfig.Builder metaConfig = agentUnitConfig.getMetaConfigBuilder();
        Entry.Builder source = metaConfig.addEntryBuilder().setKey(PowerStateSynchroniserAgent.SOURCE_KEY);
        Entry.Builder target = metaConfig.addEntryBuilder().setKey(PowerStateSynchroniserAgent.TARGET_KEY + "_1");
        for (UnitConfig unit : Registries.getUnitRegistry().getDalUnitConfigs()) {
            if (unit.getEnablingState().getValue() != EnablingState.State.ENABLED) {
                continue;
            }

            if (unit.getUnitType() == UnitType.POWER_SWITCH && source.getValue().isEmpty()) {
                sourceId = unit.getId();
                source.setValue(unit.getId());
            } else if (unit.getUnitType() == UnitType.COLORABLE_LIGHT && target.getValue().isEmpty()) {
                targetId = unit.getId();
                target.setValue(unit.getId());
            }

            if (source.hasValue() && target.hasValue()) {
                break;
            }
        }

        return agentUnitConfig.build();
    }
}
