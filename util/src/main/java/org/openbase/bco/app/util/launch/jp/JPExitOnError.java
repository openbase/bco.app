package org.openbase.bco.app.util.launch.jp;

/*-
 * #%L
 * BCO App Utility
 * %%
 * Copyright (C) 2018 - 2021 openbase.org
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

import org.openbase.jps.preset.AbstractJPBoolean;

public class JPExitOnError extends AbstractJPBoolean {


    public static final String[] COMMAND_IDENTIFIER = {"--exit-on-error"};

    public JPExitOnError() {
        super(COMMAND_IDENTIFIER);
    }

    @Override
    public String getDescription() {
        return "Flag can be used to force an application to exit right after the first error occurs.";
    }
}
