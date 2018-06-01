package org.openbase.bco.app.openhab.manager.service;

import org.openbase.bco.app.openhab.manager.transform.PowerStateTransformer;
import org.openbase.bco.dal.lib.layer.service.operation.PowerStateOperationService;
import org.openbase.bco.dal.lib.layer.unit.Unit;
import org.openbase.jul.exception.CouldNotPerformException;
import org.openbase.jul.exception.InstantiationException;
import org.openbase.jul.exception.NotAvailableException;
import rst.domotic.action.ActionFutureType.ActionFuture;
import rst.domotic.state.PowerStateType.PowerState;

import java.util.concurrent.Future;

public class PowerStateServiceImpl<ST extends PowerStateOperationService & Unit<?>> extends OpenHABService<ST> implements PowerStateOperationService {

    PowerStateServiceImpl(final ST unit) throws InstantiationException {
        super(unit);
    }

    @Override
    public Future<ActionFuture> setPowerState(PowerState powerState) throws CouldNotPerformException {
        return executeCommand(PowerStateTransformer.transform(powerState));
    }

    @Override
    public PowerState getPowerState() throws NotAvailableException {
        return unit.getPowerState();
    }
}
