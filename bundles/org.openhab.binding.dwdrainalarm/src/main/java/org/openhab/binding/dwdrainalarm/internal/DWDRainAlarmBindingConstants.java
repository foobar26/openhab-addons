/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 * <p>
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 * <p>
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 * <p>
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.dwdrainalarm.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.smarthome.core.thing.ThingTypeUID;

/**
 * The {@link DWDRainAlarmBindingConstants} class defines common constants, which are
 * used across the whole binding.
 *
 * @author Frank Egger - Initial contribution
 */
@NonNullByDefault
public class DWDRainAlarmBindingConstants {

    private static final String BINDING_ID = "dwdrainalarm";

    // List of all Thing Type UIDs
    public static final ThingTypeUID THING_TYPE_RAINALARM = new ThingTypeUID(BINDING_ID, "rainalarm");

    // List of all Channel ids
    public static final String EVENT_CHANNEL_ID_CURRENT = "current";
    public static final String EVENT_CHANNEL_ID_MAXINRADIUS = "maxInRadius";
}
