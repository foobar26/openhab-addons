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

import static org.openhab.core.thing.ThingStatus.OFFLINE;
import static org.openhab.core.thing.ThingStatus.ONLINE;
import static org.openhab.binding.dwdrainalarm.internal.DWDRainAlarmBindingConstants.*;

import org.apache.commons.lang.StringUtils;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.openhab.binding.dwdrainalarm.internal.radolan.RadolanReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.measure.Unit;
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

    private @Nullable DWDRainAlarmConfiguration config;

    private boolean inRefresh = false;

    private @Nullable ScheduledFuture<?> refreshJob;

    private RadolanReader radolanReader = new RadolanReader();

    public DWDRainAlarmHandler(Thing thing) {
        super(thing);
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
        try {
            logger.trace("Updating rain radar!");

            this.updateData();
        } catch (Throwable t) {
            logger.error("Error in rain alarm handler", t);
        }
    }

    @Override
    public void initialize() {
        logger.debug("Start initializing rain radar!");
        String thingUid = getThing().getUID().toString();
        config = getConfigAs(DWDRainAlarmConfiguration.class);
        boolean validConfig = true;
        if (config == null) {
            logger.error("Couldn't read config, disabling thing '{}'",
                    thingUid);
            validConfig = false;
        } else {
            config.setThingUid(thingUid);

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
            if (config.predictionTime < 5 || config.predictionTime > 120) {
                logger.error("DWDRainAlarm parameter interval must be in the range of 5-120 (in 5 min steps), disabling thing '{}'", thingUid);
                validConfig = false;
            }
        }

        if (validConfig) {
            logger.debug("{}", config);
            updateStatus(ONLINE);

            ScheduledFuture<?> localRefreshJob = refreshJob;
            if (localRefreshJob == null || localRefreshJob.isCancelled()) {
                logger.debug("Start refresh job at interval {} seconds.", config.interval);
                refreshJob = scheduler.scheduleWithFixedDelay(this::updateThings, INITIAL_DELAY_IN_SECONDS,
                        config.interval, TimeUnit.SECONDS);
            }

            logger.info("Rain radar for location=" + config.geolocation + " initialized !");
        } else {
            updateStatus(OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR);
        }
        logger.debug("Thing {} initialized {}", getThing().getUID(), getThing().getStatus());

        // logger.debug("Finished initializing!");
/*
        config = getConfigAs(DWDRainAlarmConfiguration.class);

        // TODO: Initialize the handler.
        // The framework requires you to return from this method quickly. Also, before leaving this method a thing
        // status from one of ONLINE, OFFLINE or UNKNOWN must be set. This might already be the real thing status in
        // case you can decide it directly.
        // In case you can not decide the thing status directly (e.g. for long running connection handshake using WAN
        // access or similar) you should set status UNKNOWN here and then decide the real status asynchronously in the
        // background.

        // set the thing status to UNKNOWN temporarily and let the background task decide for the real status.
        // the framework is then able to reuse the resources from the thing handler initialization.
        // we set this upfront to reliably check status updates in unit tests.
        updateStatus(ThingStatus.UNKNOWN);

        // Example for background initialization:
        scheduler.execute(() -> {
            boolean thingReachable = true; // <background task with long running initialization here>
            // when done do:
            if (thingReachable) {
                updateStatus(ThingStatus.ONLINE);
            } else {
                updateStatus(ThingStatus.OFFLINE);
            }
        });

        // These logging types should be primarily used by bindings
        // logger.trace("Example trace message");
        // logger.debug("Example debug message");
        // logger.warn("Example warn message");

        // Note: When initialization can NOT be done set the status with more details for further
        // analysis. See also class ThingStatusDetail for all available status details.
        // Add a description to give user information to understand why thing does not work as expected. E.g.
        // updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
        // "Can not access device as username and/or password are invalid");

 */
    }

    @Override
    public void dispose() {
        logger.debug("Dispose DWDRainAlarm handler '{}'.", getThing().getUID());
        ScheduledFuture<?> localRefreshJob = refreshJob;
        if (localRefreshJob != null && !localRefreshJob.isCancelled()) {
            logger.info("Stop refresh job.");
            if (localRefreshJob.cancel(true)) {
                refreshJob = null;
            }
        }
    }

    private void updateData() {
        try {
            if (inRefresh) {
                logger.trace("Already refreshing. Ignoring refresh request.");
                return;
            }

            ThingStatus status = getThing().getStatus();
            if (status != ThingStatus.ONLINE && status != ThingStatus.UNKNOWN) {
                logger.debug("Unable to refresh. Thing status is {}", status);
                return;
            }

            inRefresh = true;

            if (radolanReader.getLatitude() == 0) {
                radolanReader = new RadolanReader();
                radolanReader.setPredictionTime(config.predictionTime);
                Double latitude = config.latitude;
                Double longitude = config.longitude;
                radolanReader.setPosition(latitude, longitude);
            } else {
                logger.debug("Refreshing rain radar...");
                radolanReader.refresh();
            }
            Float currentValue = radolanReader.getCurrent();
            Float maxValueWithinRadius = radolanReader.getMaxRainWithinRadius(config.radius);
            Float predictionValue = radolanReader.getPrediction();

            if (getThing().getStatus() != ThingStatus.ONLINE) {
                logger.info("Rainradar recovered.");
                updateStatus(ThingStatus.ONLINE);
            }

            logger.debug("Current value: " + currentValue);
            updateState(getChannelUuid(EVENT_CHANNEL_ID_CURRENT), new DecimalType(currentValue));
            logger.debug("Max value: " + maxValueWithinRadius);
            updateState(getChannelUuid(EVENT_CHANNEL_ID_MAXINRADIUS), new DecimalType(maxValueWithinRadius));
            logger.debug("Prediction value: " + predictionValue);
            updateState(getChannelUuid(EVENT_CHANNEL_ID_PREDICTION), new DecimalType(predictionValue));

            logger.debug("Rain radar updated.");
        } catch (Throwable e) {
            logger.info("Updating rain radar failed: " + e.getMessage());
            logger.debug("Debug info for failure", e);
            try { updateStatus(ThingStatus.UNKNOWN); } catch (Throwable t) {
                logger.debug("Cannot set thing status!", e);
            }
        }
        inRefresh = false;
    }

    private ChannelUID getChannelUuid(String typeId) {
        return new ChannelUID(getThing().getUID(), typeId);
    }

    protected State getQuantityTypeState(@Nullable Number value, Unit<?> unit) {
        return (value == null) ? UnDefType.UNDEF : new QuantityType<>(value, unit);
    }

}
