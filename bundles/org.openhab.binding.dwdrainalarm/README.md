# DWDRainAlarm Binding

This binding adds information of DWD rain radar (WX-Prodct) to openHab2.

## Supported Things

This binding supports just one thing, the rainalarm.

## Discovery

You can get the current dbZ value for a given location and the max within a given radius around the location. 
By this it is possible to predict rain for a given area.
Still to come: Predictive values for given location.

## Binding Configuration

No binding configuration needed.

## Thing Configuration

All Things require the parameter `geolocation` (as `<latitude>,<longitude>`) for which the calculation is done. 
Optionally, a refresh `interval` (in seconds) can be defined, by default a thing updates itself after 5 minutes (default for WX Product of DWD).
Optionally, a `radius` distince in KM can be defined, by default it is set to 10 km.

## Channels

* **thing** `rainalarm`
    * **channel**
        * `current_rain_radar` (Number)
        * `max_rain_radar` (Number)

## Full Example

demo.things:

```
// Berlin
dwdrainalarm:rainalarm:home [ geolocation="52.520008,13.404954", interval="300", radius="10" ]
```

demo.items:

```
Number current_rain_radar "Current Rain Radar" { channel="dwdrainalarm:rainalarm:home:current" }
Number max_rain_radar "Max Rain Radar" { channel="dwdrainalarm:rainalarm:home:maxInRadius" }
```
