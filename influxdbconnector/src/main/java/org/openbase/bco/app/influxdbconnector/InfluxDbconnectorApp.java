package org.openbase.bco.app.influxdbconnector;

/*-
 * #%L
 * BCO InfluxDB Connector
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


import org.influxdata.client.*;
import org.influxdata.client.write.Point;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import org.influxdata.client.domain.WritePrecision;
import org.influxdata.client.domain.Bucket;
import org.influxdata.client.write.events.WriteErrorEvent;
import org.influxdata.client.write.events.WriteSuccessEvent;
import org.openbase.bco.dal.control.layer.unit.app.AbstractAppController;
import org.openbase.bco.dal.lib.layer.service.ServiceStateProvider;
import org.openbase.bco.dal.lib.layer.service.Services;
import org.openbase.bco.dal.lib.layer.unit.Unit;
import org.openbase.bco.dal.lib.layer.unit.UnitRemote;
import org.openbase.bco.dal.remote.layer.unit.CustomUnitPool;
import org.openbase.bco.dal.remote.layer.unit.Units;
import org.openbase.bco.registry.remote.Registries;
import org.openbase.jul.exception.*;
import org.openbase.jul.exception.InstantiationException;
import org.openbase.jul.exception.printer.ExceptionPrinter;
import org.openbase.jul.pattern.Observer;
import org.openbase.jul.schedule.GlobalCachedExecutorService;
import org.openbase.type.domotic.action.ActionDescriptionType.ActionDescription;
import org.openbase.type.domotic.service.ServiceDescriptionType;
import org.openbase.type.domotic.service.ServiceTemplateType;
import org.openbase.type.domotic.service.ServiceTempusTypeType;
import org.openbase.type.domotic.state.ActivationStateType.ActivationState;
import org.openbase.type.domotic.unit.UnitConfigType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

import static org.openbase.bco.dal.lib.layer.service.Services.resolveStateValue;

public class InfluxDbconnectorApp extends AbstractAppController {

    private Logger logger = LoggerFactory.getLogger(getClass());

    private static final Integer READ_TIMEOUT = 60;
    private static final Integer WRITE_TIMEOUT = 60;
    private static final Integer CONNECT_TIMOUT = 40;
    private static final Integer MAX_TIMEOUT = 300000;
    private static final Integer ADDITIONAL_TIMEOUT = 60000;
    private WriteApi writeApi;
    private Integer databaseTimeout = 60000;
    private Bucket bucket;
    private char[] token;
    private Future task;
    private String databaseUrl;
    private String bucketName;
    private InfluxDBClient influxDBClient;
    private Integer batchTime;
    private Integer batchLimit;
    private CustomUnitPool customUnitPool;
    private Observer<ServiceStateProvider<Message>, Message> unitStateObserver;
    private String org;


    public InfluxDbconnectorApp() throws InstantiationException {


    }


    @Override
    public UnitConfigType.UnitConfig applyConfigUpdate(UnitConfigType.UnitConfig config) throws CouldNotPerformException, InterruptedException {
        config = super.applyConfigUpdate(config);

        bucketName = generateVariablePool().getValue("INFLUXDB_BUCKET");
        batchTime = Integer.valueOf(generateVariablePool().getValue("INFLUXDB_BATCH_TIME"));
        batchLimit = Integer.valueOf(generateVariablePool().getValue("INFLUXDB_BATCH_LIMIT"));
        databaseUrl = generateVariablePool().getValue("INFLUXDB_URL");
        token = (generateVariablePool().getValue("INFLUXDB_TOKEN")).toCharArray();
        org = generateVariablePool().getValue("INFLUXDB_ORG");
        return config;
    }


    @Override
    protected ActionDescription execute(ActivationState activationState) {

        task = GlobalCachedExecutorService.submit(() -> {
            logger.debug("Execute influx db connector");
            boolean dbConnected = false;

            while (!dbConnected) {
                try {
                    connectToDatabase();
                    dbConnected = checkConnection();
                } catch (CouldNotPerformException ex) {
                    logger.warn("Could not reach influxdb server at " + databaseUrl + ". Try again in " + databaseTimeout / 1000 + " seconds!");
                    ExceptionPrinter.printHistory(ex, logger);

                    try {
                        Thread.sleep(databaseTimeout);
                        if (databaseTimeout < MAX_TIMEOUT) databaseTimeout += ADDITIONAL_TIMEOUT;
                    } catch (InterruptedException exc) {
                        ExceptionPrinter.printHistory(exc, logger);
                        Thread.currentThread().interrupt(); // set interrupt flag
                    }
                }


            }
            boolean foundBucket = false;
            while (!foundBucket) {
                try {
                    foundBucket = getDatabaseBucket();

                } catch (CouldNotPerformException ex) {
                    logger.warn("Could not get bucket. Try again in " + databaseTimeout / 1000 + " seconds!");

                    ExceptionPrinter.printHistory(ex, logger);
                    try {
                        Thread.sleep(databaseTimeout);
                    } catch (InterruptedException exc) {
                        ExceptionPrinter.printHistory(exc, logger);
                        Thread.currentThread().interrupt(); // set interrupt flag                    }

                    }
                }
            }


            try {

                customUnitPool = new CustomUnitPool();

                unitStateObserver = (source, data) -> saveInDB((Unit) source.getServiceProvider(), source.getServiceType(), data);


                init();
            } catch (InitializationException | InstantiationException ex) {
                ExceptionPrinter.printHistory(ex, logger);
            } catch (InterruptedException ex) {
                ExceptionPrinter.printHistory(ex, logger);
                Thread.currentThread().interrupt(); // set interrupt flag
            }

        });
        return activationState.getResponsibleAction();
    }

    @Override
    protected void stop(ActivationState activationState) throws CouldNotPerformException, InterruptedException {
        if (task != null && !task.isDone()) {
            task.cancel(false);
        }
    }

    public void init() throws InitializationException, InterruptedException {
        try {
            for (UnitConfigType.UnitConfig unitConfig : Registries.getUnitRegistry(true).getUnitConfigs()) {
                final UnitRemote<?> unit = Units.getUnit(unitConfig, true);

                try {
                    for (ServiceDescriptionType.ServiceDescription serviceDescription : unit.getUnitTemplate().getServiceDescriptionList()) {

                        if (serviceDescription.getPattern() != ServiceTemplateType.ServiceTemplate.ServicePattern.PROVIDER) {
                            continue;
                        }
                        saveInDB(unit, serviceDescription.getServiceType(), Services.invokeProviderServiceMethod(serviceDescription.getServiceType(), ServiceTempusTypeType.ServiceTempusType.ServiceTempus.CURRENT, unit));
                    }
                } catch (CouldNotPerformException ex) {
                    ExceptionPrinter.printHistory("Could not saveInDB " + unit, ex, logger);
                }
            }

            customUnitPool.init();
            customUnitPool.addObserver(unitStateObserver);
        } catch (CouldNotPerformException ex) {
            throw new InitializationException(this, ex);
        }
    }

    private void saveInDB(Unit<?> unit, ServiceTemplateType.ServiceTemplate.ServiceType serviceType, Message serviceState) {
        try {
            String initiator;
            try {
                initiator = Services.getResponsibleAction(serviceState).getActionInitiator().getInitiatorType().name().toLowerCase();
            } catch (NotAvailableException ex) {
                // in this case we use the system as initiator because responsible actions are not available for pure provider services and those are always system generated.
                initiator = "system";
            }
            Map<String, String> stateValuesMap = resolveStateValueToMap(serviceState);
            Point point = Point.measurement(serviceType.toString().toLowerCase())
                    .addTag("alias", unit.getConfig().getAlias(0))
                    .addTag("initiator", initiator)
                    .addTag("unitId", unit.getId())
                    .addTag("unitType", unit.getUnitType().name().toLowerCase())
                    .time(Instant.now().toEpochMilli(), WritePrecision.MS);


            for (Map.Entry<String, String> entry : stateValuesMap.entrySet()) {
                // detect numbers with regex
                if (entry.getValue().matches("-?\\d+(\\.\\d+)?")) {
                    point.addField(entry.getKey(), Double.valueOf(entry.getValue()));

                } else {
                    point.addTag(entry.getKey(), entry.getValue());
                }
            }
            writeApi.writePoint(bucketName, org, point);
        } catch (CouldNotPerformException ex) {
            ExceptionPrinter.printHistory("Could not saveInDB " + serviceType.name() + " of " + unit, ex, logger);
        }
    }


    public Map<String, String> resolveStateValueToMap(Message serviceState) throws CouldNotPerformException {
        final Map<String, String> stateValues = new HashMap<>();
        for (Descriptors.FieldDescriptor fieldDescriptor : serviceState.getDescriptorForType().getFields()) {
            String stateName = fieldDescriptor.getName();
            String stateType = fieldDescriptor.getType().toString().toLowerCase();

            // filter invalid states
            if (stateName == null || stateType == null) {
                logger.warn("Could not detect datatype of " + stateName);
            }

            // filter general service fields
            switch (stateName) {
                case "last_value_occurrence":
                case "timestamp":
                case "responsible_action":
                case "type":
                case "rgb_color":
                case "frame_id":
                    continue;
            }

            // filter data units
            if (stateName.endsWith("data_unit")) {
                continue;
            }

            String stateValue = serviceState.getField(fieldDescriptor).toString();

            try {
                if (fieldDescriptor.getType() == Descriptors.FieldDescriptor.Type.MESSAGE) {
                    if (fieldDescriptor.isRepeated()) {
                        List<String> types = new ArrayList<>();

                        for (int i = 0; i < serviceState.getRepeatedFieldCount(fieldDescriptor); i++) {
                            final Object repeatedFieldEntry = serviceState.getRepeatedField(fieldDescriptor, i);
                            if (repeatedFieldEntry instanceof Message) {
                                types.add("[" + resolveStateValue((Message) repeatedFieldEntry).toString() + "]");
                            }
                            types.add(repeatedFieldEntry.toString());
                        }
                        stateType = types.toString().toLowerCase();
                    } else {
                        stateValue = resolveStateValue((Message) serviceState.getField(fieldDescriptor)).toString();
                    }
                }
            } catch (InvalidStateException ex) {
                logger.warn("Could not process value of " + fieldDescriptor.getName());
                continue;
            }

            // filter values
            switch (stateValue) {
                case "":
                case "NaN":
                    continue;
                default:
                    break;
            }

            stateValues.put(fieldDescriptor.getName(), stateValue.toLowerCase());
        }
        return stateValues;
    }

    private boolean checkConnection() throws CouldNotPerformException {
        if (influxDBClient.health().getStatus().getValue() != "pass") {
            throw new CouldNotPerformException("Could not connect to database server at " + databaseUrl + "!");

        }
        // initiate WriteApi
        WriteOptions writeoptions = WriteOptions.builder().batchSize(batchLimit).flushInterval(batchTime).build();
        writeApi = influxDBClient.getWriteApi(writeoptions);
        writeApi.listenEvents(WriteSuccessEvent.class, event -> {
            logger.debug("Successfully wrote data into db");
        });
        writeApi.listenEvents(WriteErrorEvent.class, event -> {
            Throwable exception = event.getThrowable();
            logger.warn(exception.getMessage());
        });
        logger.debug("Connected to Influxdb at " + databaseUrl);

        return true;


    }

    private void connectToDatabase() {
        logger.debug(" Try to connect to influxDB at " + databaseUrl);
        try{
        influxDBClient = InfluxDBClientFactory
                .create(databaseUrl + "?readTimeout=" + READ_TIMEOUT + "&connectTimeout=" + 1 + "&writeTimeout=" + WRITE_TIMEOUT + "&logLevel=BASIC", token);
        logger.debug("ddd");
        }
        catch (Exception ex){
            logger.debug(ex.getMessage());
        }
        logger.debug("done");
    }


    private boolean getDatabaseBucket() throws CouldNotPerformException {
        logger.debug("Get bucket " + bucketName);
        bucket = influxDBClient.getBucketsApi().findBucketByName(bucketName);
        if (bucket != null) return true;

        throw new CouldNotPerformException("Could not get bucket " + bucketName + "!");


    }
}
