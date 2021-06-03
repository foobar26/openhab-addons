/**
 * Copyright (c) 2010-2021 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.dwdrainalarm.internal;

import static org.openhab.binding.dwdrainalarm.internal.DWDRainAlarmBindingConstants.*;

import java.util.Set;
import java.util.Collections;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.annotations.Component;

/**
 * The {@link DWDRainAlarmHandlerFactory} is responsible for creating things and thing
 * handlers.
 *
 * @author Frank Egger - Initial contribution
 */
@NonNullByDefault
@Component(configurationPid = "binding.dwdrainalarm", service = ThingHandlerFactory.class)
public class DWDRainAlarmHandlerFactory extends BaseThingHandlerFactory {

    private static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = Collections.singleton(THING_TYPE_RAINALARM);

    public DWDRainAlarmHandlerFactory() {
    }

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();

        if (THING_TYPE_RAINALARM.equals(thingTypeUID)) {
            return new DWDRainAlarmHandler(thing);
        }

        return null;
    }
}
