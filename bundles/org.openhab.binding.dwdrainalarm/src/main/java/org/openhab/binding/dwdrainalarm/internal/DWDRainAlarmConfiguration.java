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

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;
import org.eclipse.jdt.annotation.Nullable;

/**
 * The {@link DWDRainAlarmConfiguration} class contains fields mapping thing configuration parameters.
 *
 * @author Frank Egger - Initial contribution
 */
public class DWDRainAlarmConfiguration {
    public @Nullable String geolocation;
    public @Nullable Double latitude;
    public @Nullable Double longitude;
    public int interval = 300;
    private @Nullable String thingUid;

    /**
     * Splits the geolocation into latitude and longitude.
     */
    public void parseGeoLocation() {
        String[] geoParts = StringUtils.split(geolocation, ",");
        if (geoParts.length == 2) {
            latitude = toDouble(geoParts[0]);
            longitude = toDouble(geoParts[1]);
        }
    }

    private @Nullable Double toDouble(String value) {
        try {
            return Double.parseDouble(StringUtils.trimToNull(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Sets the thing uid as string.
     */
    public void setThingUid(String thingUid) {
        this.thingUid = thingUid;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE).append("thing", thingUid)
                .append("geolocation", geolocation).append("interval", interval).toString();
    }
}
