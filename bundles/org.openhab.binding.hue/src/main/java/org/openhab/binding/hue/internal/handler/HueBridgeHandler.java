/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
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
package org.openhab.binding.hue.internal.handler;

import static org.eclipse.smarthome.core.thing.Thing.*;
import static org.openhab.binding.hue.internal.HueBindingConstants.*;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.config.core.status.ConfigStatusMessage;
import org.eclipse.smarthome.core.library.types.HSBType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.binding.ConfigStatusBridgeHandler;
import org.eclipse.smarthome.core.types.Command;
import org.openhab.binding.hue.internal.ApiVersionUtils;
import org.openhab.binding.hue.internal.Config;
import org.openhab.binding.hue.internal.ConfigUpdate;
import org.openhab.binding.hue.internal.FullConfig;
import org.openhab.binding.hue.internal.FullGroup;
import org.openhab.binding.hue.internal.FullLight;
import org.openhab.binding.hue.internal.FullSensor;
import org.openhab.binding.hue.internal.HueBridge;
import org.openhab.binding.hue.internal.HueConfigStatusMessage;
import org.openhab.binding.hue.internal.State;
import org.openhab.binding.hue.internal.StateUpdate;
import org.openhab.binding.hue.internal.config.HueBridgeConfig;
import org.openhab.binding.hue.internal.discovery.HueLightDiscoveryService;
import org.openhab.binding.hue.internal.exceptions.ApiException;
import org.openhab.binding.hue.internal.exceptions.DeviceOffException;
import org.openhab.binding.hue.internal.exceptions.EntityNotAvailableException;
import org.openhab.binding.hue.internal.exceptions.LinkButtonException;
import org.openhab.binding.hue.internal.exceptions.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link HueBridgeHandler} is the handler for a hue bridge and connects it to
 * the framework. All {@link HueLightHandler}s use the {@link HueBridgeHandler} to execute the actual commands.
 *
 * @author Dennis Nobel - Initial contribution
 * @author Oliver Libutzki - Adjustments
 * @author Kai Kreuzer - improved state handling
 * @author Andre Fuechsel - implemented getFullLights(), startSearch()
 * @author Thomas H??fer - added thing properties
 * @author Stefan Bu??weiler - Added new thing status handling
 * @author Jochen Hiller - fixed status updates, use reachable=true/false for state compare
 * @author Denis Dudnik - switched to internally integrated source of Jue library
 * @author Samuel Leisering - Added support for sensor API
 * @author Christoph Weitkamp - Added support for sensor API
 * @author Laurent Garnier - Added support for groups
 */
@NonNullByDefault
public class HueBridgeHandler extends ConfigStatusBridgeHandler implements HueClient {

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Collections.singleton(THING_TYPE_BRIDGE);

    private static final long BYPASS_MIN_DURATION_BEFORE_CMD = 1500L;

    private static final String DEVICE_TYPE = "EclipseSmartHome";

    private final Logger logger = LoggerFactory.getLogger(HueBridgeHandler.class);

    private final Map<String, @Nullable FullLight> lastLightStates = new ConcurrentHashMap<>();
    private final Map<String, @Nullable FullSensor> lastSensorStates = new ConcurrentHashMap<>();
    private final Map<String, @Nullable FullGroup> lastGroupStates = new ConcurrentHashMap<>();

    private @Nullable HueLightDiscoveryService discoveryService;
    private final Map<String, @Nullable LightStatusListener> lightStatusListeners = new ConcurrentHashMap<>();
    private final Map<String, @Nullable SensorStatusListener> sensorStatusListeners = new ConcurrentHashMap<>();
    private final Map<String, @Nullable GroupStatusListener> groupStatusListeners = new ConcurrentHashMap<>();

    final ReentrantLock pollingLock = new ReentrantLock();

    abstract class PollingRunnable implements Runnable {
        @Override
        public void run() {
            try {
                pollingLock.lock();
                if (!lastBridgeConnectionState) {
                    // if user is not set in configuration try to create a new user on Hue bridge
                    if (hueBridgeConfig.getUserName() == null) {
                        hueBridge.getFullConfig();
                    }
                    lastBridgeConnectionState = tryResumeBridgeConnection();
                }
                if (lastBridgeConnectionState) {
                    doConnectedRun();
                    if (thing.getStatus() != ThingStatus.ONLINE) {
                        updateStatus(ThingStatus.ONLINE);
                    }
                }
            } catch (UnauthorizedException | IllegalStateException e) {
                if (isReachable(hueBridge.getIPAddress())) {
                    lastBridgeConnectionState = false;
                    onNotAuthenticated();
                } else if (lastBridgeConnectionState || thing.getStatus() == ThingStatus.INITIALIZING) {
                    lastBridgeConnectionState = false;
                    onConnectionLost();
                }
            } catch (ApiException | IOException e) {
                if (hueBridge != null && lastBridgeConnectionState) {
                    logger.debug("Connection to Hue Bridge {} lost.", hueBridge.getIPAddress());
                    lastBridgeConnectionState = false;
                    onConnectionLost();
                }
            } catch (RuntimeException e) {
                logger.warn("An unexpected error occurred: {}", e.getMessage(), e);
                lastBridgeConnectionState = false;
                onConnectionLost();
            } finally {
                pollingLock.unlock();
            }
        }

        protected abstract void doConnectedRun() throws IOException, ApiException;

        private boolean isReachable(String ipAddress) {
            try {
                // note that InetAddress.isReachable is unreliable, see
                // http://stackoverflow.com/questions/9922543/why-does-inetaddress-isreachable-return-false-when-i-can-ping-the-ip-address
                // That's why we do an HTTP access instead

                // If there is no connection, this line will fail
                hueBridge.authenticate("invalid");
            } catch (IOException e) {
                return false;
            } catch (ApiException e) {
                if (e.getMessage().contains("SocketTimeout") || e.getMessage().contains("ConnectException")
                        || e.getMessage().contains("SocketException")
                        || e.getMessage().contains("NoRouteToHostException")) {
                    return false;
                } else {
                    // this seems to be only an authentication issue
                    return true;
                }
            }
            return true;
        }
    }

    private final Runnable sensorPollingRunnable = new PollingRunnable() {
        @Override
        protected void doConnectedRun() throws IOException, ApiException {
            Map<String, @Nullable FullSensor> lastSensorStateCopy = new HashMap<>(lastSensorStates);

            final HueLightDiscoveryService discovery = discoveryService;

            for (final FullSensor sensor : hueBridge.getSensors()) {
                String sensorId = sensor.getId();

                final SensorStatusListener sensorStatusListener = sensorStatusListeners.get(sensorId);
                if (sensorStatusListener == null) {
                    logger.trace("Hue sensor '{}' added.", sensorId);

                    if (discovery != null && !lastSensorStateCopy.containsKey(sensorId)) {
                        discovery.addSensorDiscovery(sensor);
                    }

                    lastSensorStates.put(sensorId, sensor);
                } else {
                    if (sensorStatusListener.onSensorStateChanged(sensor)) {
                        lastSensorStates.put(sensorId, sensor);
                    }
                }
                lastSensorStateCopy.remove(sensorId);
            }

            // Check for removed sensors
            lastSensorStateCopy.forEach((sensorId, sensor) -> {
                logger.trace("Hue sensor '{}' removed.", sensorId);
                lastSensorStates.remove(sensorId);

                final SensorStatusListener sensorStatusListener = sensorStatusListeners.get(sensorId);
                if (sensorStatusListener != null) {
                    sensorStatusListener.onSensorRemoved();
                }

                if (discovery != null && sensor != null) {
                    discovery.removeSensorDiscovery(sensor);
                }
            });
        }
    };

    private final Runnable lightPollingRunnable = new PollingRunnable() {
        @Override
        protected void doConnectedRun() throws IOException, ApiException {
            Map<String, @Nullable FullLight> lastLightStateCopy = new HashMap<>(lastLightStates);

            List<FullLight> lights;
            if (ApiVersionUtils.supportsFullLights(hueBridge.getVersion())) {
                lights = hueBridge.getFullLights();
            } else {
                lights = hueBridge.getFullConfig().getLights();
            }

            final HueLightDiscoveryService discovery = discoveryService;

            for (final FullLight fullLight : lights) {
                final String lightId = fullLight.getId();

                final LightStatusListener lightStatusListener = lightStatusListeners.get(lightId);
                if (lightStatusListener == null) {
                    logger.trace("Hue light '{}' added.", lightId);

                    if (discovery != null && !lastLightStateCopy.containsKey(lightId)) {
                        discovery.addLightDiscovery(fullLight);
                    }

                    lastLightStates.put(lightId, fullLight);
                } else {
                    if (lightStatusListener.onLightStateChanged(fullLight)) {
                        lastLightStates.put(lightId, fullLight);
                    }
                }
                lastLightStateCopy.remove(lightId);
            }

            // Check for removed lights
            lastLightStateCopy.forEach((lightId, light) -> {
                logger.trace("Hue light '{}' removed.", lightId);
                lastLightStates.remove(lightId);

                final LightStatusListener lightStatusListener = lightStatusListeners.get(lightId);
                if (lightStatusListener != null) {
                    lightStatusListener.onLightRemoved();
                }

                if (discovery != null && light != null) {
                    discovery.removeLightDiscovery(light);
                }
            });

            Map<String, @Nullable FullGroup> lastGroupStateCopy = new HashMap<>(lastGroupStates);

            for (final FullGroup fullGroup : hueBridge.getGroups()) {
                State groupState = new State();
                boolean on = false;
                int sumBri = 0;
                int nbBri = 0;
                State colorRef = null;
                HSBType firstColorHsb = null;
                for (String lightId : fullGroup.getLights()) {
                    FullLight light = lastLightStates.get(lightId);
                    if (light != null) {
                        final State lightState = light.getState();
                        logger.trace("Group {}: light {}: on {} bri {} hue {} sat {} temp {} mode {} XY {}",
                                fullGroup.getName(), light.getName(), lightState.isOn(), lightState.getBrightness(),
                                lightState.getHue(), lightState.getSaturation(), lightState.getColorTemperature(),
                                lightState.getColorMode(), lightState.getXY());
                        if (lightState.isOn()) {
                            on = true;
                            sumBri += lightState.getBrightness();
                            nbBri++;
                            if (lightState.getColorMode() != null) {
                                HSBType lightHsb = LightStateConverter.toHSBType(lightState);
                                if (firstColorHsb == null) {
                                    // first color light
                                    firstColorHsb = lightHsb;
                                    colorRef = lightState;
                                } else if (!lightHsb.equals(firstColorHsb)) {
                                    colorRef = null;
                                }
                            }
                        }
                    }
                }
                groupState.setOn(on);
                groupState.setBri(nbBri == 0 ? 0 : sumBri / nbBri);
                if (colorRef != null) {
                    groupState.setColormode(colorRef.getColorMode());
                    groupState.setHue(colorRef.getHue());
                    groupState.setSaturation(colorRef.getSaturation());
                    groupState.setColorTemperature(colorRef.getColorTemperature());
                    groupState.setXY(colorRef.getXY());
                }
                fullGroup.setState(groupState);
                logger.trace("Group {} ({}): on {} bri {} hue {} sat {} temp {} mode {} XY {}", fullGroup.getName(),
                        fullGroup.getType(), groupState.isOn(), groupState.getBrightness(), groupState.getHue(),
                        groupState.getSaturation(), groupState.getColorTemperature(), groupState.getColorMode(),
                        groupState.getXY());

                String groupId = fullGroup.getId();

                final GroupStatusListener groupStatusListener = groupStatusListeners.get(groupId);
                if (groupStatusListener == null) {
                    logger.trace("Hue group '{}' ({}) added (nb lights {}).", groupId, fullGroup.getName(),
                            fullGroup.getLights().size());

                    if (discovery != null && !lastGroupStateCopy.containsKey(groupId)) {
                        discovery.addGroupDiscovery(fullGroup);
                    }

                    lastGroupStates.put(groupId, fullGroup);
                } else {
                    if (groupStatusListener.onGroupStateChanged(fullGroup)) {
                        lastGroupStates.put(groupId, fullGroup);
                    }
                }
                lastGroupStateCopy.remove(groupId);
            }

            // Check for removed groups
            lastGroupStateCopy.forEach((groupId, group) -> {
                logger.trace("Hue group '{}' removed.", groupId);
                lastGroupStates.remove(groupId);

                final GroupStatusListener groupStatusListener = groupStatusListeners.get(groupId);
                if (groupStatusListener != null) {
                    groupStatusListener.onGroupRemoved();
                }

                if (discovery != null && group != null) {
                    discovery.removeGroupDiscovery(group);
                }
            });
        }
    };

    private boolean lastBridgeConnectionState = false;

    private boolean propertiesInitializedSuccessfully = false;

    private @Nullable ScheduledFuture<?> lightPollingJob;
    private @Nullable ScheduledFuture<?> sensorPollingJob;

    private @NonNullByDefault({}) HueBridge hueBridge = null;
    private @NonNullByDefault({}) HueBridgeConfig hueBridgeConfig = null;

    public HueBridgeHandler(Bridge bridge) {
        super(bridge);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // not needed
    }

    @Override
    public void updateLightState(LightStatusListener listener, FullLight light, StateUpdate stateUpdate,
            long fadeTime) {
        if (hueBridge != null) {
            listener.setPollBypass(BYPASS_MIN_DURATION_BEFORE_CMD);
            hueBridge.setLightState(light, stateUpdate).thenAccept(result -> {
                try {
                    hueBridge.handleErrors(result);
                    listener.setPollBypass(fadeTime);
                } catch (Exception e) {
                    listener.unsetPollBypass();
                    handleStateUpdateException(listener, light, stateUpdate, fadeTime, e);
                }
            }).exceptionally(e -> {
                listener.unsetPollBypass();
                handleStateUpdateException(listener, light, stateUpdate, fadeTime, e);
                return null;
            });
        } else {
            logger.debug("No bridge connected or selected. Cannot set light state.");
        }
    }

    @Override
    public void updateSensorState(FullSensor sensor, StateUpdate stateUpdate) {
        if (hueBridge != null) {
            hueBridge.setSensorState(sensor, stateUpdate).thenAccept(result -> {
                try {
                    hueBridge.handleErrors(result);
                } catch (Exception e) {
                    handleStateUpdateException(sensor, stateUpdate, e);
                }
            }).exceptionally(e -> {
                handleStateUpdateException(sensor, stateUpdate, e);
                return null;
            });
        } else {
            logger.debug("No bridge connected or selected. Cannot set sensor state.");
        }
    }

    @Override
    public void updateSensorConfig(FullSensor sensor, ConfigUpdate configUpdate) {
        if (hueBridge != null) {
            hueBridge.updateSensorConfig(sensor, configUpdate).thenAccept(result -> {
                try {
                    hueBridge.handleErrors(result);
                } catch (Exception e) {
                    handleConfigUpdateException(sensor, configUpdate, e);
                }
            }).exceptionally(e -> {
                handleConfigUpdateException(sensor, configUpdate, e);
                return null;
            });
        } else {
            logger.debug("No bridge connected or selected. Cannot set sensor config.");
        }
    }

    @Override
    public void updateGroupState(FullGroup group, StateUpdate stateUpdate, long fadeTime) {
        if (hueBridge != null) {
            setGroupPollBypass(group, BYPASS_MIN_DURATION_BEFORE_CMD);
            hueBridge.setGroupState(group, stateUpdate).thenAccept(result -> {
                try {
                    hueBridge.handleErrors(result);
                    setGroupPollBypass(group, fadeTime);
                } catch (Exception e) {
                    unsetGroupPollBypass(group);
                    handleStateUpdateException(group, stateUpdate, e);
                }
            }).exceptionally(e -> {
                unsetGroupPollBypass(group);
                handleStateUpdateException(group, stateUpdate, e);
                return null;
            });
        } else {
            logger.debug("No bridge connected or selected. Cannot set group state.");
        }
    }

    private void setGroupPollBypass(FullGroup group, long bypassTime) {
        group.getLights().forEach((lightId) -> {
            final LightStatusListener listener = lightStatusListeners.get(lightId);
            if (listener != null) {
                listener.setPollBypass(bypassTime);
            }
        });
    }

    private void unsetGroupPollBypass(FullGroup group) {
        group.getLights().forEach((lightId) -> {
            final LightStatusListener listener = lightStatusListeners.get(lightId);
            if (listener != null) {
                listener.unsetPollBypass();
            }
        });
    }

    private void handleStateUpdateException(LightStatusListener listener, FullLight light, StateUpdate stateUpdate,
            long fadeTime, Throwable e) {
        if (e instanceof DeviceOffException) {
            if (stateUpdate.getColorTemperature() != null && stateUpdate.getBrightness() == null) {
                // If there is only a change of the color temperature, we do not want the light
                // to be turned on (i.e. change its brightness).
                return;
            } else {
                updateLightState(listener, light, LightStateConverter.toOnOffLightState(OnOffType.ON), fadeTime);
                updateLightState(listener, light, stateUpdate, fadeTime);
            }
        } else if (e instanceof IOException) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        } else if (e instanceof EntityNotAvailableException) {
            logger.debug("Error while accessing light: {}", e.getMessage(), e);
            final HueLightDiscoveryService discovery = discoveryService;
            if (discovery != null) {
                discovery.removeLightDiscovery(light);
            }

            listener.onLightGone();
        } else if (e instanceof ApiException) {
            // This should not happen - if it does, it is most likely some bug that should be reported.
            logger.warn("Error while accessing light: {}", e.getMessage(), e);
        } else if (e instanceof IllegalStateException) {
            logger.trace("Error while accessing light: {}", e.getMessage());
        }
    }

    private void handleStateUpdateException(FullSensor sensor, StateUpdate stateUpdate, Throwable e) {
        if (e instanceof IOException) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        } else if (e instanceof EntityNotAvailableException) {
            logger.debug("Error while accessing sensor: {}", e.getMessage(), e);
            final HueLightDiscoveryService discovery = discoveryService;
            if (discovery != null) {
                discovery.removeSensorDiscovery(sensor);
            }

            final SensorStatusListener listener = sensorStatusListeners.get(sensor.getId());
            if (listener != null) {
                listener.onSensorGone();
            }
        } else if (e instanceof ApiException) {
            // This should not happen - if it does, it is most likely some bug that should be reported.
            logger.warn("Error while accessing sensor: {}", e.getMessage(), e);
        } else if (e instanceof IllegalStateException) {
            logger.trace("Error while accessing sensor: {}", e.getMessage());
        }
    }

    private void handleStateUpdateException(FullGroup group, StateUpdate stateUpdate, Throwable e) {
        if (e instanceof IOException) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        } else if (e instanceof EntityNotAvailableException) {
            logger.debug("Error while accessing group: {}", e.getMessage(), e);
            final HueLightDiscoveryService discovery = discoveryService;
            if (discovery != null) {
                discovery.removeGroupDiscovery(group);
            }

            final GroupStatusListener listener = groupStatusListeners.get(group.getId());
            if (listener != null) {
                listener.onGroupGone();
            }
        } else if (e instanceof ApiException) {
            // This should not happen - if it does, it is most likely some bug that should be reported.
            logger.warn("Error while accessing group: {}", e.getMessage(), e);
        } else if (e instanceof IllegalStateException) {
            logger.trace("Error while accessing group: {}", e.getMessage());
        }
    }

    private void handleConfigUpdateException(FullSensor sensor, ConfigUpdate configUpdate, Throwable e) {
        if (e instanceof IOException) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        } else if (e instanceof EntityNotAvailableException) {
            logger.debug("Error while accessing sensor: {}", e.getMessage(), e);
            final HueLightDiscoveryService discovery = discoveryService;
            if (discovery != null) {
                discovery.removeSensorDiscovery(sensor);
            }

            final SensorStatusListener listener = sensorStatusListeners.get(sensor.getId());
            if (listener != null) {
                listener.onSensorGone();
            }
        } else if (e instanceof ApiException) {
            // This should not happen - if it does, it is most likely some bug that should be reported.
            logger.warn("Error while accessing sensor: {}", e.getMessage(), e);
        } else if (e instanceof IllegalStateException) {
            logger.trace("Error while accessing sensor: {}", e.getMessage());
        }
    }

    private void startLightPolling() {
        ScheduledFuture<?> job = lightPollingJob;
        if (job == null || job.isCancelled()) {
            long lightPollingInterval;
            int configPollingInterval = hueBridgeConfig.getPollingInterval();
            if (configPollingInterval < 1) {
                lightPollingInterval = TimeUnit.SECONDS.toSeconds(10);
                logger.info("Wrong configuration value for polling interval. Using default value: {}s",
                        lightPollingInterval);
            } else {
                lightPollingInterval = configPollingInterval;
            }
            lightPollingJob = scheduler.scheduleWithFixedDelay(lightPollingRunnable, 1, lightPollingInterval,
                    TimeUnit.SECONDS);
        }
    }

    private void stopLightPolling() {
        ScheduledFuture<?> job = lightPollingJob;
        if (job != null) {
            job.cancel(true);
        }
        lightPollingJob = null;
    }

    private void startSensorPolling() {
        ScheduledFuture<?> job = sensorPollingJob;
        if (job == null || job.isCancelled()) {
            int configSensorPollingInterval = hueBridgeConfig.getSensorPollingInterval();
            if (configSensorPollingInterval > 0) {
                long sensorPollingInterval;
                if (configSensorPollingInterval < 50) {
                    sensorPollingInterval = TimeUnit.MILLISECONDS.toMillis(500);
                    logger.info("Wrong configuration value for sensor polling interval. Using default value: {}ms",
                            sensorPollingInterval);
                } else {
                    sensorPollingInterval = configSensorPollingInterval;
                }
                sensorPollingJob = scheduler.scheduleWithFixedDelay(sensorPollingRunnable, 1, sensorPollingInterval,
                        TimeUnit.MILLISECONDS);
            }
        }
    }

    private void stopSensorPolling() {
        ScheduledFuture<?> job = sensorPollingJob;
        if (job != null) {
            job.cancel(true);
        }
        sensorPollingJob = null;
    }

    @Override
    public void dispose() {
        logger.debug("Handler disposed.");
        stopLightPolling();
        stopSensorPolling();
        if (hueBridge != null) {
            hueBridge = null;
        }
    }

    @Override
    public void initialize() {
        logger.debug("Initializing hue bridge handler.");
        hueBridgeConfig = getConfigAs(HueBridgeConfig.class);

        String ip = hueBridgeConfig.getIpAddress();
        if (ip == null || ip.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error-no-ip-address");
        } else {
            if (hueBridge == null) {
                hueBridge = new HueBridge(ip, hueBridgeConfig.getPort(), hueBridgeConfig.getProtocol(), scheduler);
                hueBridge.setTimeout(5000);
            }
            onUpdate();
        }
    }

    public @Nullable String getUserName() {
        return hueBridgeConfig == null ? null : hueBridgeConfig.getUserName();
    }

    private synchronized void onUpdate() {
        if (hueBridge != null) {
            startLightPolling();
            startSensorPolling();
        }
    }

    /**
     * This method is called whenever the connection to the {@link HueBridge} is lost.
     */
    public void onConnectionLost() {
        logger.debug("Bridge connection lost. Updating thing status to OFFLINE.");
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.NONE, "@text/offline.bridge-connection-lost");
    }

    /**
     * This method is called whenever the connection to the {@link HueBridge} is resumed.
     *
     * @throws ApiException if the physical device does not support this API call
     * @throws IOException if the physical device could not be reached
     */
    private void onConnectionResumed() throws IOException, ApiException {
        logger.debug("Bridge connection resumed.");

        if (!propertiesInitializedSuccessfully) {
            FullConfig fullConfig = hueBridge.getFullConfig();
            Config config = fullConfig.getConfig();
            if (config != null) {
                Map<String, String> properties = editProperties();
                String serialNumber = config.getBridgeId().substring(0, 6) + config.getBridgeId().substring(10);
                properties.put(PROPERTY_SERIAL_NUMBER, serialNumber);
                properties.put(PROPERTY_MODEL_ID, config.getModelId());
                properties.put(PROPERTY_MAC_ADDRESS, config.getMACAddress());
                properties.put(PROPERTY_FIRMWARE_VERSION, config.getSoftwareVersion());
                updateProperties(properties);
                propertiesInitializedSuccessfully = true;
            }
        }
    }

    /**
     * Check USER_NAME config for null. Call onConnectionResumed() otherwise.
     *
     * @return True if USER_NAME was not null.
     * @throws ApiException if the physical device does not support this API call
     * @throws IOException if the physical device could not be reached
     */
    private boolean tryResumeBridgeConnection() throws IOException, ApiException {
        logger.debug("Connection to Hue Bridge {} established.", hueBridge.getIPAddress());
        if (hueBridgeConfig.getUserName() == null) {
            logger.warn(
                    "User name for Hue bridge authentication not available in configuration. Setting ThingStatus to OFFLINE.");
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error-no-username");
            return false;
        } else {
            onConnectionResumed();
            return true;
        }
    }

    /**
     * This method is called whenever the connection to the {@link HueBridge} is available,
     * but requests are not allowed due to a missing or invalid authentication.
     * <p>
     * If there is a user name available, it attempts to re-authenticate. Otherwise new authentication credentials will
     * be requested from the bridge.
     *
     * @param bridge the hue bridge the connection is not authorized
     * @return returns {@code true} if re-authentication was successful, {@code false} otherwise
     */
    public boolean onNotAuthenticated() {
        if (hueBridge == null) {
            return false;
        }
        String userName = hueBridgeConfig.getUserName();
        if (userName == null) {
            createUser();
        } else {
            try {
                hueBridge.authenticate(userName);
                return true;
            } catch (Exception e) {
                handleAuthenticationFailure(e, userName);
            }
        }
        return false;
    }

    private void createUser() {
        try {
            String newUser = createUserOnPhysicalBridge();
            updateBridgeThingConfiguration(newUser);
        } catch (LinkButtonException ex) {
            handleLinkButtonNotPressed(ex);
        } catch (Exception ex) {
            handleExceptionWhileCreatingUser(ex);
        }
    }

    private String createUserOnPhysicalBridge() throws IOException, ApiException {
        logger.info("Creating new user on Hue bridge {} - please press the pairing button on the bridge.",
                hueBridgeConfig.getIpAddress());
        String userName = hueBridge.link(DEVICE_TYPE);
        logger.info("User has been successfully added to Hue bridge.");
        return userName;
    }

    private void updateBridgeThingConfiguration(String userName) {
        Configuration config = editConfiguration();
        config.put(USER_NAME, userName);
        try {
            updateConfiguration(config);
            logger.debug("Updated configuration parameter '{}'", USER_NAME);
            hueBridgeConfig = getConfigAs(HueBridgeConfig.class);
        } catch (IllegalStateException e) {
            logger.trace("Configuration update failed.", e);
            logger.warn("Unable to update configuration of Hue bridge.");
            logger.warn("Please configure the user name manually.");
        }
    }

    private void handleAuthenticationFailure(Exception ex, String userName) {
        logger.warn("User is not authenticated on Hue bridge {}", hueBridgeConfig.getIpAddress());
        logger.warn("Please configure a valid user or remove user from configuration to generate a new one.");
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                "@text/offline.conf-error-invalid-username");
    }

    private void handleLinkButtonNotPressed(LinkButtonException ex) {
        logger.debug("Failed creating new user on Hue bridge: {}", ex.getMessage());
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                "@text/offline.conf-error-press-pairing-button");
    }

    private void handleExceptionWhileCreatingUser(Exception ex) {
        logger.warn("Failed creating new user on Hue bridge", ex);
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                "@text/offline.conf-error-creation-username");
    }

    @Override
    public boolean registerDiscoveryListener(HueLightDiscoveryService listener) {
        if (discoveryService == null) {
            discoveryService = listener;
            getFullLights().forEach(listener::addLightDiscovery);
            getFullSensors().forEach(listener::addSensorDiscovery);
            getFullGroups().forEach(listener::addGroupDiscovery);
            return true;
        }

        return false;
    }

    @Override
    public boolean unregisterDiscoveryListener() {
        if (discoveryService != null) {
            discoveryService = null;
            return true;
        }

        return false;
    }

    @Override
    public boolean registerLightStatusListener(LightStatusListener lightStatusListener) {
        final String lightId = lightStatusListener.getLightId();
        if (!lightStatusListeners.containsKey(lightId)) {
            lightStatusListeners.put(lightId, lightStatusListener);
            final FullLight lastLightState = lastLightStates.get(lightId);
            if (lastLightState != null) {
                lightStatusListener.onLightAdded(lastLightState);
            }

            return true;
        }
        return false;
    }

    @Override
    public boolean unregisterLightStatusListener(LightStatusListener lightStatusListener) {
        return lightStatusListeners.remove(lightStatusListener.getLightId()) != null;
    }

    @Override
    public boolean registerSensorStatusListener(SensorStatusListener sensorStatusListener) {
        final String sensorId = sensorStatusListener.getSensorId();
        if (!sensorStatusListeners.containsKey(sensorId)) {
            sensorStatusListeners.put(sensorId, sensorStatusListener);
            final FullSensor lastSensorState = lastSensorStates.get(sensorId);
            if (lastSensorState != null) {
                sensorStatusListener.onSensorAdded(lastSensorState);
            }
            return true;
        }

        return false;
    }

    @Override
    public boolean unregisterSensorStatusListener(SensorStatusListener sensorStatusListener) {
        return sensorStatusListeners.remove(sensorStatusListener.getSensorId()) != null;
    }

    @Override
    public boolean registerGroupStatusListener(GroupStatusListener groupStatusListener) {
        final String groupId = groupStatusListener.getGroupId();
        if (!groupStatusListeners.containsKey(groupId)) {
            groupStatusListeners.put(groupId, groupStatusListener);
            final FullGroup lastGroupState = lastGroupStates.get(groupId);
            if (lastGroupState != null) {
                groupStatusListener.onGroupAdded(lastGroupState);
            }
            return true;
        }

        return false;
    }

    @Override
    public boolean unregisterGroupStatusListener(GroupStatusListener groupStatusListener) {
        return groupStatusListeners.remove(groupStatusListener.getGroupId()) != null;
    }

    @Override
    public @Nullable FullLight getLightById(String lightId) {
        return lastLightStates.get(lightId);
    }

    @Override
    public @Nullable FullSensor getSensorById(String sensorId) {
        return lastSensorStates.get(sensorId);
    }

    @Override
    public @Nullable FullGroup getGroupById(String groupId) {
        return lastGroupStates.get(groupId);
    }

    public List<FullLight> getFullLights() {
        List<FullLight> ret = withReAuthentication("search for new lights", () -> {
            return hueBridge.getFullLights();
        });
        return ret != null ? ret : Collections.emptyList();
    }

    public List<FullSensor> getFullSensors() {
        List<FullSensor> ret = withReAuthentication("search for new sensors", () -> {
            return hueBridge.getSensors();
        });
        return ret != null ? ret : Collections.emptyList();
    }

    public List<FullGroup> getFullGroups() {
        List<FullGroup> ret = withReAuthentication("search for new groups", () -> {
            return hueBridge.getGroups();
        });
        return ret != null ? ret : Collections.emptyList();
    }

    public void startSearch() {
        withReAuthentication("start search mode", () -> {
            hueBridge.startSearch();
            return null;
        });
    }

    public void startSearch(List<String> serialNumbers) {
        withReAuthentication("start search mode", () -> {
            hueBridge.startSearch(serialNumbers);
            return null;
        });
    }

    private @Nullable <T> T withReAuthentication(String taskDescription, Callable<T> runnable) {
        if (hueBridge != null) {
            try {
                try {
                    return runnable.call();
                } catch (UnauthorizedException | IllegalStateException e) {
                    lastBridgeConnectionState = false;
                    if (onNotAuthenticated()) {
                        return runnable.call();
                    }
                }
            } catch (Exception e) {
                logger.debug("Bridge cannot {}.", taskDescription, e);
            }
        }
        return null;
    }

    @Override
    public Collection<ConfigStatusMessage> getConfigStatus() {
        // The bridge IP address to be used for checks
        Collection<ConfigStatusMessage> configStatusMessages;

        // Check whether an IP address is provided
        String ip = hueBridgeConfig.getIpAddress();
        if (ip == null || ip.isEmpty()) {
            configStatusMessages = Collections.singletonList(ConfigStatusMessage.Builder.error(HOST)
                    .withMessageKeySuffix(HueConfigStatusMessage.IP_ADDRESS_MISSING).withArguments(HOST).build());
        } else {
            configStatusMessages = Collections.emptyList();
        }

        return configStatusMessages;
    }
}
