package org.openbase.bco.app.cloud.connector;

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

import com.google.gson.*;
import com.google.protobuf.Message;
import io.socket.client.Ack;
import io.socket.client.IO;
import io.socket.client.Manager;
import io.socket.client.Socket;
import io.socket.engineio.client.Transport;
import org.openbase.bco.app.cloud.connector.jp.JPCloudServerURI;
import org.openbase.bco.app.cloud.connector.mapping.lib.ErrorCode;
import org.openbase.bco.authentication.lib.TokenStore;
import org.openbase.bco.dal.lib.layer.service.ServiceJSonProcessor;
import org.openbase.bco.dal.lib.layer.unit.UnitRemote;
import org.openbase.bco.dal.remote.unit.Units;
import org.openbase.bco.dal.remote.unit.location.LocationRemote;
import org.openbase.bco.dal.remote.unit.user.UserRemote;
import org.openbase.bco.registry.remote.Registries;
import org.openbase.bco.registry.unit.lib.UnitRegistry;
import org.openbase.jps.core.JPService;
import org.openbase.jps.exception.JPNotAvailableException;
import org.openbase.jul.exception.CouldNotPerformException;
import org.openbase.jul.exception.InitializationException;
import org.openbase.jul.exception.NotAvailableException;
import org.openbase.jul.exception.printer.ExceptionPrinter;
import org.openbase.jul.extension.rst.processing.LabelProcessor;
import org.openbase.jul.iface.Launchable;
import org.openbase.jul.iface.VoidInitializable;
import org.openbase.jul.pattern.Observable;
import org.openbase.jul.pattern.Observer;
import org.openbase.jul.schedule.GlobalCachedExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rst.configuration.LabelType.Label;
import rst.domotic.activity.ActivityConfigType.ActivityConfig;
import rst.domotic.registry.UnitRegistryDataType.UnitRegistryData;
import rst.domotic.service.ServiceStateDescriptionType.ServiceStateDescription;
import rst.domotic.service.ServiceTemplateType.ServiceTemplate.ServiceType;
import rst.domotic.state.ActivityMultiStateType.ActivityMultiState;
import rst.domotic.state.UserTransitStateType.UserTransitState;
import rst.domotic.unit.UnitConfigType.UnitConfig;
import rst.domotic.unit.UnitTemplateType.UnitTemplate.UnitType;
import rst.domotic.unit.scene.SceneConfigType.SceneConfig;

import java.util.*;
import java.util.concurrent.*;

/**
 * @author <a href="mailto:pleminoq@openbase.org">Tamino Huxohl</a>
 */
public class SocketWrapper implements Launchable<Void>, VoidInitializable {

    private static final String LOGIN_EVENT = "login";
    private static final String REGISTER_EVENT = "register";
    private static final String REQUEST_SYNC_EVENT = "requestSync";

    private static final String INTENT_REGISTER_SCENE = "register_scene";
    private static final String INTENT_USER_ACTIVITY = "user_activity";
    private static final String INTENT_USER_TRANSIT = "user_transit";

    private static final String ID_KEY = "id";
    private static final String TOKEN_KEY = "accessToken";
    private static final String SUCCESS_KEY = "success";
    private static final String ERROR_KEY = "error";

    private static final Logger LOGGER = LoggerFactory.getLogger(SocketWrapper.class);

    private final String userId;
    private final TokenStore tokenStore;

    private JsonObject loginData;
    private Socket socket;
    private boolean active, loggedIn;
    private String agentUserId;

    private final ServiceJSonProcessor serviceJSonProcessor;
    private final UnitRegistryObserver unitRegistryObserver;

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final JsonParser jsonParser = new JsonParser();
    private CompletableFuture<Void> loginFuture;

    public SocketWrapper(final String userId, final TokenStore tokenStore) {
        this(userId, tokenStore, null);
    }

    public SocketWrapper(final String userId, final TokenStore tokenStore, final JsonObject loginData) {
        this.userId = userId;
        this.tokenStore = tokenStore;
        this.loginData = loginData;
        this.unitRegistryObserver = new UnitRegistryObserver();
        this.active = false;
        this.serviceJSonProcessor = new ServiceJSonProcessor();
    }

    @Override
    public void init() throws InitializationException {
        try {
            // validate that the token store contains an authorization token for the given user
            if (!tokenStore.contains(userId + "@BCO")) {
                try {
                    throw new NotAvailableException("Token for user[" + userId + "] for BCO");
                } catch (NotAvailableException ex) {
                    throw new InitializationException(this, ex);
                }
            }

            // validate that either login data is set or the token store contains a token for the cloud
            if (loginData == null && !tokenStore.contains(userId + "@Cloud")) {
                try {
                    throw new NotAvailableException("Login data for user[" + userId + "] for cloud");
                } catch (NotAvailableException ex) {
                    throw new InitializationException(this, ex);
                }
            }

            final String bcoId = Registries.getUnitRegistry().getUnitConfigByAlias(UnitRegistry.BCO_USER_ALIAS).getId();
            agentUserId = userId + "@" + bcoId;

            // create socket
            socket = IO.socket(JPService.getProperty(JPCloudServerURI.class).getValue());
            // add id to header for cloud server
            socket.io().on(Manager.EVENT_TRANSPORT, args -> {
                Transport transport = (Transport) args[0];

                transport.on(Transport.EVENT_REQUEST_HEADERS, args1 -> {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, List<String>> headers = (Map<String, List<String>>) args1[0];
                        // combination of bco and user id to header
                        headers.put(ID_KEY, Collections.singletonList(agentUserId));
                    } catch (Exception ex) {
                        ExceptionPrinter.printHistory(ex, LOGGER);
                    }
                });
            });
            // add listener to socket events
            socket.on(Socket.EVENT_CONNECT, objects -> {
                // when socket is connected
                LOGGER.info("Socket of user[" + userId + "] connected");
                login();
            }).on(Socket.EVENT_MESSAGE, objects -> {
                // received a message
                LOGGER.info("Socket of user[" + userId + "] received a request");
                // handle request
                handleRequest(objects[0], (Ack) objects[objects.length - 1]);
            }).on(Socket.EVENT_DISCONNECT, objects -> {
                // reconnection is automatically done by the socket API, just print that disconnected
                LOGGER.info("Socket of user[" + userId + "] disconnected");
            }).on(INTENT_USER_TRANSIT, objects -> {
                LOGGER.info("Socket of user[" + userId + "] received transit state request");
                handleUserTransitUpdate(objects[0], (Ack) objects[objects.length - 1]);
            }).on(INTENT_USER_ACTIVITY, objects -> {
                LOGGER.info("Socket of user[" + userId + "] received activity request");
                handleActivity(objects[0], (Ack) objects[objects.length - 1]);
            }).on(INTENT_REGISTER_SCENE, objects -> {
                LOGGER.info("Socket of user[" + userId + "] received register scene request");
                handleSceneRegistration(objects[0], (Ack) objects[objects.length - 1]);
            });

            // add observer to registry that triggers sync requests on changes
            Registries.getUnitRegistry().addDataObserver(unitRegistryObserver);
        } catch (JPNotAvailableException | CouldNotPerformException ex) {
            throw new InitializationException(this, ex);
        }
    }

    private void handleRequest(final Object request, final Ack acknowledgement) {
        try {
            // parse as json
            final JsonElement parse = jsonParser.parse((String) request);
            LOGGER.info("Request: " + gson.toJson(parse));

            //TODO: fulfillment handlers also need authorization token...
            // handle request and create response
            LOGGER.info("Call handler");
            final JsonObject jsonObject = FulfillmentHandler.handleRequest(parse.getAsJsonObject(), agentUserId, tokenStore.getToken(userId + "@BCO"));
            final String response = gson.toJson(jsonObject);
            LOGGER.info("Handler produced response: " + response);
            // send back response
            acknowledgement.call(response);
        } catch (Exception ex) {
            // send back an error response
            final JsonObject response = new JsonObject();
            final JsonObject payload = new JsonObject();
            response.add(FulfillmentHandler.PAYLOAD_KEY, payload);
            FulfillmentHandler.setError(payload, ex, ErrorCode.UNKNOWN_ERROR);
            acknowledgement.call(gson.toJson(response));
        }
    }

    private Future<Void> register() {
        final CompletableFuture<Void> registrationFuture = new CompletableFuture<>();
        socket.emit(REGISTER_EVENT, gson.toJson(loginData), (Ack) objects -> {
            try {
                final JsonObject response = jsonParser.parse(objects[0].toString()).getAsJsonObject();
                if (response.get(SUCCESS_KEY).getAsBoolean()) {
                    // clear login data
                    loginData = null;
                    // save received token
                    tokenStore.addToken(userId + "@Cloud", response.get(TOKEN_KEY).getAsString());
                    // complete registration future, so that waiting tasks know that is is finished
                    registrationFuture.complete(null);
                } else {
                    LOGGER.error("Could not login user[" + userId + "] at BCO Cloud: " + response.get(ERROR_KEY).getAsString());
                    registrationFuture.completeExceptionally(new CouldNotPerformException("Could not register user"));
                }
            } catch (ArrayIndexOutOfBoundsException | ClassCastException ex) {
                ExceptionPrinter.printHistory("Unexpected response for login request", ex, LOGGER);
                registrationFuture.completeExceptionally(new CouldNotPerformException("Could not register user"));
            }
        });
        return registrationFuture;
    }

    private void login() {
        // this has to be done on another thread because the socket library uses a single event thread
        // so without this it is not possible to wait for the registration to finish
        GlobalCachedExecutorService.submit(() -> {
            if (loginData != null) {
                // register user
                try {
                    register().get(10, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    ExceptionPrinter.printHistory(ex, LOGGER);
                    loginFuture.completeExceptionally(new CouldNotPerformException("Could not register user[" + userId + "] at BCO Cloud", ex));
                    return;
                } catch (ExecutionException | TimeoutException ex) {
                    ExceptionPrinter.printHistory(ex, LOGGER);
                    loginFuture.completeExceptionally(new CouldNotPerformException("Could not register user[" + userId + "] at BCO Cloud", ex));
                    return;
                }
            }

            final JsonObject loginInfo = new JsonObject();
            try {
                loginInfo.addProperty(TOKEN_KEY, tokenStore.getToken(userId + "@Cloud"));
            } catch (NotAvailableException ex) {
                ExceptionPrinter.printHistory("Could not login user[" + userId + "] at BCO Cloud", ex, LOGGER);
                return;
            }

            LOGGER.info("Send loginInfo [" + gson.toJson(loginInfo) + "]");
            socket.emit(LOGIN_EVENT, gson.toJson(loginInfo), (Ack) objects -> {
                try {
                    final JsonObject response = jsonParser.parse(objects[0].toString()).getAsJsonObject();
                    if (response.get(SUCCESS_KEY).getAsBoolean()) {
                        loggedIn = true;
                        LOGGER.info("Logged in [" + userId + "] successfully");
                        loginFuture.complete(null);
                        // trigger initial database sync
                        requestSync();
                    } else {
                        LOGGER.info("Could not login user[" + userId + "] at BCO Cloud: " + response.get(ERROR_KEY));
                        loginFuture.completeExceptionally(new CouldNotPerformException("Could not login user[" + userId + "] at BCO Cloud: " + response.get(ERROR_KEY)));
                    }
                } catch (ArrayIndexOutOfBoundsException | ClassCastException ex) {
                    ExceptionPrinter.printHistory("Unexpected response for login request", ex, LOGGER);
                    loginFuture.completeExceptionally(new CouldNotPerformException("Could not login user[" + userId + "] at BCO Cloud", ex));
                }
            });
        });
    }

    private void handleUserTransitUpdate(final Object object, final Ack acknowledgement) {
        final String transit = (String) object;
        LOGGER.info("Received user transit " + transit);
        try {
            final UserTransitState.State state = Enum.valueOf(UserTransitState.State.class, transit);
            final UserRemote userRemote = Units.getUnit(userId, false, UserRemote.class);
            userRemote.setUserTransitState(UserTransitState.newBuilder().setValue(state).build()).get(3, TimeUnit.SECONDS);
            acknowledgement.call("Alles klar");
        } catch (InterruptedException ex) {
            acknowledgement.call("Entschuldige. Es ist ein Fehler aufgetreten.");
            ExceptionPrinter.printHistory(ex, LOGGER);
            Thread.currentThread().interrupt();
            // this should not happen since wait for data is not called
        } catch (CouldNotPerformException | ExecutionException | TimeoutException | IllegalArgumentException ex) {
            acknowledgement.call("Entschuldige. Es ist ein Fehler aufgetreten.");
            ExceptionPrinter.printHistory(ex, LOGGER);
        }
    }

    private void handleActivity(final Object object, final Ack acknowledgement) {
        final String activityRepresentation = (String) object;
        LOGGER.info("Received activity representation: " + activityRepresentation);
        ActivityConfig activity = null;
        try {
            outer:
            for (final ActivityConfig activityConfig : Registries.getActivityRegistry().getActivityConfigs()) {
                for (Label.MapFieldEntry entry : activityConfig.getLabel().getEntryList()) {
                    for (String label : entry.getValueList()) {
                        if (label.toLowerCase().contains(activityRepresentation)) {
                            activity = activityConfig;
                            break outer;
                        }
                    }
                }
            }

            if (activity == null) {
                acknowledgement.call("Ich kann die von die ausgeführte Aktivität " + activityRepresentation + " nicht finden");
                return;
            }

            final UserRemote userRemote;
            userRemote = Units.getUnit(userId, true, UserRemote.class);
            //TODO: when to add and when to replace
            ActivityMultiState.Builder activityMultiState = ActivityMultiState.newBuilder().addActivityId(activity.getId());
            userRemote.setActivityMultiState(activityMultiState.build()).get(3, TimeUnit.SECONDS);
            acknowledgement.call("Deine Aktivität wurde auf " + LabelProcessor.getBestMatch(activity.getLabel()) + " gesetzt.");
        } catch (CouldNotPerformException | InterruptedException | ExecutionException ex) {
            acknowledgement.call("Entschuldige. Es ist ein Fehler aufgetreten.");
            ExceptionPrinter.printHistory(ex, LOGGER);
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } catch (TimeoutException ex) {
            acknowledgement.call("Deine Aktivität wird auf " + activityRepresentation + " gesetzt");
        }
    }

    private void handleSceneRegistration(final Object object, final Ack acknowledgement) {
        //TODO: if taking to long give feedback that process is still running?
//        final long MAX_WAIING_TIME = 5000;
//        long startingTime = System.currentTimeMillis();

        final JsonObject data = jsonParser.parse(object.toString()).getAsJsonObject();
        LOGGER.info("Received scene registration data:\n" + gson.toJson(data));
        final UnitConfig.Builder sceneUnitConfig = UnitConfig.newBuilder().setUnitType(UnitType.SCENE);

        try {
            UnitConfig location = Registries.getUnitRegistry().getRootLocationConfig();
            if (data.has("location")) {
                final String locationLabel = data.get("location").getAsString();
                List<UnitConfig> locations = Registries.getUnitRegistry().getUnitConfigsByLabelAndUnitType(locationLabel, UnitType.LOCATION);
                if (locations.size() == 0) {
                    //TODO: error response
                } else if (locations.size() == 2) {
                    //TODO: query for more info
                } else {
                    location = locations.get(0);
                }
            }

            if (data.has("label")) {
                final String label = data.get("label").getAsString();
                final Label.MapFieldEntry.Builder entry = sceneUnitConfig.getLabelBuilder().addEntryBuilder();
                entry.setKey(Locale.GERMAN.getLanguage());
                entry.addValue(label);

                // make sure label is available for this location
                for (UnitConfig unitConfig : Registries.getUnitRegistry().getUnitConfigsByLabelAndLocation(label, location.getId())) {
                    if (unitConfig.getUnitType() == UnitType.SCENE) {
                        acknowledgement.call("");
                    }
                }
            }

            final List<ServiceType> serviceTypes = Arrays.asList(ServiceType.ACTIVATION_STATE_SERVICE,
                    ServiceType.BRIGHTNESS_STATE_SERVICE, ServiceType.COLOR_STATE_SERVICE, ServiceType.POWER_STATE_SERVICE);

            SceneConfig.Builder sceneConfig = sceneUnitConfig.getSceneConfigBuilder();
            LocationRemote locationRemote = Units.getUnit(location, false, LocationRemote.class);
            for (ServiceType serviceType : serviceTypes) {
                for (Object internalUnit : locationRemote.getServiceRemote(serviceType).getInternalUnits()) {
                    final UnitRemote<?> unitRemote = (UnitRemote) internalUnit;
                    if (!unitRemote.isDataAvailable()) {
                        try {
                            unitRemote.waitForData(50, TimeUnit.MILLISECONDS);
                        } catch (CouldNotPerformException ex) {
                            LOGGER.warn("Skip unit[" + unitRemote.getLabel() + "] for scene creation because data not available");
                            continue;
                        }
                    }
                    final ServiceStateDescription.Builder serviceStateDescription = sceneConfig.addRequiredServiceStateDescriptionBuilder();
                    serviceStateDescription.setUnitId(unitRemote.getId());
                    serviceStateDescription.setServiceType(serviceType);
                    serviceStateDescription.setUnitType(unitRemote.getUnitType());
                    Message serviceState = unitRemote.getServiceState(serviceType);
                    serviceStateDescription.setServiceAttribute(serviceJSonProcessor.serialize(serviceState));
                    serviceStateDescription.setServiceAttributeType(serviceJSonProcessor.getServiceAttributeType(serviceState));
                }
            }

            try {
                UnitConfig unitConfig = Registries.getUnitRegistry().registerUnitConfig(sceneUnitConfig.build()).get(1, TimeUnit.SECONDS);
                acknowledgement.call("Die Szene " + LabelProcessor.getLabelByLanguage(Locale.GERMAN, unitConfig.getLabel()) + " wurde erfolgreich registriert.");
            } catch (ExecutionException ex) {
                acknowledgement.call("Entschuldige. Es ist ein Fehler aufgetreten");
                ExceptionPrinter.printHistory(ex, LOGGER);
            } catch (TimeoutException e) {
                acknowledgement.call("Die Szene wird gerade registriert");
            }
        } catch (InterruptedException ex) {
            acknowledgement.call("Entschuldige. Es ist ein Fehler aufgetreten");
            Thread.currentThread().interrupt();
            ExceptionPrinter.printHistory(ex, LOGGER);
        } catch (CouldNotPerformException ex) {
            acknowledgement.call("Entschuldige. Es ist ein Fehler aufgetreten");
            ExceptionPrinter.printHistory(ex, LOGGER);
        }
    }

    @Override
    public void activate() throws CouldNotPerformException {
        loginFuture = new CompletableFuture<>();
        if (socket == null) {
            throw new CouldNotPerformException("Cannot activate before initialization");
        }
        active = true;
        socket.connect();
    }

    @Override
    public void deactivate() throws CouldNotPerformException {
        if (socket == null) {
            throw new CouldNotPerformException("Cannot deactivate before initialization");
        }
        socket.disconnect();
        active = false;
        loginFuture = null;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    private boolean isLoggedIn() {
        return loggedIn;
    }

    private class UnitRegistryObserver implements Observer<UnitRegistryData> {
        private JsonObject lastSyncResponse = null;

        @Override
        public void update(final Observable<UnitRegistryData> source, final UnitRegistryData data) throws Exception {

            // build a response for a sync request
            final JsonObject syncResponse = new JsonObject();
            FulfillmentHandler.handleSync(syncResponse, agentUserId);
            // return if the response to a sync has not changed since the last time
            if (lastSyncResponse != null && lastSyncResponse.hashCode() == syncResponse.hashCode()) {
                return;
            }
            lastSyncResponse = syncResponse;

            // trigger sync if socket is connected and logged in
            // if the user is not logged the login process will trigger an update anyway
            if (isLoggedIn()) {
                requestSync();

                // debug print
                JsonObject test = new JsonObject();
                test.addProperty(FulfillmentHandler.REQUEST_ID_KEY, "12345678");
                test.add(FulfillmentHandler.PAYLOAD_KEY, syncResponse);
                LOGGER.info("new sync[" + isLoggedIn() + "]:\n" + gson.toJson(test));
            }
        }
    }

    private void requestSync() {
        socket.emit(REQUEST_SYNC_EVENT, (Ack) objects -> {
            final JsonObject response = jsonParser.parse(objects[0].toString()).getAsJsonObject();
            if (response.has(SUCCESS_KEY)) {
                final boolean success = response.get(SUCCESS_KEY).getAsBoolean();
                if (success) {
                    LOGGER.info("Successfully performed sync request for user[" + userId + "]");
                } else {
                    LOGGER.warn("Could not perform sync for user[" + userId + "]: " + response.get(ERROR_KEY));
                }
            }
        });
    }

    public Future<Void> getLoginFuture() {
        return loginFuture;
    }
}