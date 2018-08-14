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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.openbase.jul.exception.FatalImplementationErrorException;
import org.openbase.jul.exception.printer.ExceptionPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rst.domotic.authentication.AuthenticatedValueType.AuthenticatedValue;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * @author <a href="mailto:pleminoq@openbase.org">Tamino Huxohl</a>
 */
public class RegistrationHelper {

    private static final Integer SALT_LENGTH = 16;
    private static final String HASH_ALGORITHM = "SHA-512";

    public static final String EMAIL_HASH_KEY = "email_hash";
    public static final String PASSWORD_HASH_KEY = "password_hash";
    public static final String PASSWORD_SALT_KEY = "password_salt";
    public static final String AUTHORIZATION_TOKEN_KEY = "authorization_token";
    public static final String AUTO_START_KEY = "auto_start";

    private static final Logger LOGGER = LoggerFactory.getLogger(RegistrationHelper.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Gson gson = new Gson();

    /**
     * Create a string which can be send to the cloud connector for an initial user registration.
     * This method will create a json object as explained in the documentation of {@link CloudConnectorApp#connect(AuthenticatedValue)}
     * and convert it to a string.
     * To do this a salt will be generated and the password will be hashed together with the salt.
     * Both are then encoded using Base64 and added as properties to a json object. The authorization token and
     * auto start flag are added to the json object as they are.
     *
     * @param password           the password the user
     * @param authorizationToken the authorization token used for the user
     * @param autoStart          an auto start flag
     * @return a string representation of a json object as defined above
     */
    public static String createRegistrationData(final String password, final String authorizationToken, final boolean autoStart) {
        // generate salt
        final byte[] saltBytes = new byte[SALT_LENGTH];
        SECURE_RANDOM.nextBytes(saltBytes);
        final String passwordSalt = Base64.getEncoder().encodeToString(saltBytes);

        // generate password hash
        final String passwordHash = hash(password, passwordSalt);

        // generate JsonObject
        final JsonObject loginData = new JsonObject();
        loginData.addProperty(PASSWORD_HASH_KEY, passwordHash);
        loginData.addProperty(PASSWORD_SALT_KEY, passwordSalt);
        loginData.addProperty(AUTHORIZATION_TOKEN_KEY, authorizationToken);
        loginData.addProperty(AUTO_START_KEY, autoStart);
        return gson.toJson(loginData);
    }

    /**
     * Hash a set of strings and encode the resulting hash with Base64.
     *
     * @param values the string which should be hashed toghether
     * @return the hashed and encoded value
     */
    public static String hash(final String... values) {
        try {
            final MessageDigest hashGenerator = MessageDigest.getInstance(HASH_ALGORITHM);
            for (final String value : values) {
                hashGenerator.update(value.getBytes());
            }
            return Base64.getEncoder().encodeToString(hashGenerator.digest());
        } catch (NoSuchAlgorithmException ex) {
            ExceptionPrinter.printHistory(new FatalImplementationErrorException(RegistrationHelper.class, ex), LOGGER);
            return null;
        }
    }
}