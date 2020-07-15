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

import static org.eclipse.smarthome.core.thing.ThingStatus.OFFLINE;
import static org.eclipse.smarthome.core.thing.ThingStatus.ONLINE;
import static org.openhab.binding.dwdrainalarm.internal.DWDRainAlarmBindingConstants.*;

import org.apache.commons.lang.StringUtils;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.i18n.TimeZoneProvider;
import org.eclipse.smarthome.core.scheduler.CronScheduler;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.openhab.binding.dwdrainalarm.internal.radolan.RadolanReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The {@link DWDRainAlarmHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Frank Egger - Initial contribution
 */
@NonNullByDefault
public class DWDRainAlarmHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(DWDRainAlarmHandler.class);
    private static final long INITIAL_DELAY_IN_SECONDS = 15;

    private @Nullable
    DWDRainAlarmConfiguration config;

    /** Scheduler to schedule jobs */
    private final CronScheduler cronScheduler;

    private final TimeZoneProvider timeZoneProvider;

    private @Nullable ScheduledFuture<?> refreshJob;

    private RadolanReader radolanReader = new RadolanReader();

    public DWDRainAlarmHandler(Thing thing, final CronScheduler scheduler, final TimeZoneProvider timeZoneProvider) {
        super(thing);
        this.cronScheduler = scheduler;
        this.timeZoneProvider = timeZoneProvider;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            scheduler.schedule(this::updateThings, INITIAL_DELAY_IN_SECONDS, TimeUnit.SECONDS);
        } else {
            logger.debug("The DWDRainAlarm binding is a read-only binding and cannot handle command '{}'.", command);
        }
    }

    private void updateThings() {
        this.updateThing();
    }

    @Override
    public void initialize() {
        // logger.debug("Start initializing!");
        String thingUid = getThing().getUID().toString();
        config = getConfigAs(DWDRainAlarmConfiguration.class);
        config.setThingUid(thingUid);
        boolean validConfig = true;

        if (StringUtils.trimToNull(config.geolocation) == null) {
            logger.error("DWDRainAlarm parameter geolocation is mandatory and must be configured, disabling thing '{}'",
                    thingUid);
            validConfig = false;
        } else {
            config.parseGeoLocation();
        }
        if (config.latitude == null || config.longitude == null) {
            logger.error(
                    "DWDRainAlarm parameters geolocation could not be split into latitude and longitude, disabling thing '{}'",
                    thingUid);
            validConfig = false;
        }
        if (config.interval < 1 || config.interval > 86400) {
            logger.error("DWDRainAlarm parameter interval must be in the range of 1-86400, disabling thing '{}'", thingUid);
            validConfig = false;
        }

        if (validConfig) {
            logger.debug("{}", config);
            updateStatus(ONLINE);

            Double latitude = config.latitude;
            Double longitude = config.longitude;
            radolanReader.initializePosition(latitude, longitude);

            ScheduledFuture<?> localRefreshJob = refreshJob;
            if (localRefreshJob == null || localRefreshJob.isCancelled()) {
                logger.debug("Start refresh job at interval {} seconds.", config.interval);
                refreshJob = scheduler.scheduleWithFixedDelay(this::updateThings, INITIAL_DELAY_IN_SECONDS,
                        config.interval, TimeUnit.SECONDS);
            }

        } else {
            updateStatus(OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR);
        }
        logger.debug("Thing {} initialized {}", getThing().getUID(), getThing().getStatus());

        // logger.debug("Finished initializing!");

        // Note: When initialization can NOT be done set the status with more details for further
        // analysis. See also class ThingStatusDetail for all available status details.
        // Add a description to give user information to understand why thing does not work as expected. E.g.
        // updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
        // "Can not access device as username and/or password are invalid");
    }

    @Override
    public void dispose() {
        logger.debug("Dispose DWDRainAlarm handler '{}'.", getThing().getUID());
        ScheduledFuture<?> localRefreshJob = refreshJob;
        if (localRefreshJob != null && !localRefreshJob.isCancelled()) {
            logger.debug("Stop refresh job.");
            if (localRefreshJob.cancel(true)) {
                refreshJob = null;
            }
        }
    }

    private void updateThing() {
        this.updateData();
    }

    private void updateData() {
        radolanReader.updateCurrent();
    }

}
