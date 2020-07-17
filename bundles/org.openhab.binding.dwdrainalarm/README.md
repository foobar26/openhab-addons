# DWDRainAlarm Binding

This binding adds information of DWD rain radar (WX-Prodct) to openHab2.
You can find detailed info on the DWD product [here](https://www.dwd.de/DE/leistungen/radarprodukte/radarkomposit_wx.html).

This binding is based on the Java library `com.bitplan.radolan`: 
* http://www.bitplan.com/index.php/Radolan
* https://github.com/BITPlan/com.bitplan.radolan

## Supported Things

This binding supports just one thing, the rainalarm.

## Discovery

You can get the current and prediction dbZ value for a given location plus the max within a given radius around the location. 
By this it is possible to predict rain for a given area.

## Binding Configuration

No binding configuration needed.

## Thing Configuration

All Things require the parameter `geolocation` (as `<latitude>,<longitude>`) for which the calculation is done. 
Optionally, a refresh `interval` (in seconds) can be defined, by default a thing updates itself after 5 minutes (default for WX Product of DWD).
Optionally, a `radius` distince in KM can be defined, by default it is set to 10 km.
Optionally, a `prediction` time (5-120mins, in 5 min steps)

## Channels

* **thing** `rainalarm`
    * **channel**
        * `current_rain_radar` (Number)
        * `max_rain_radar` (Number)
        * `prediction` (Number)

## Full Example

demo.things:

```
// Berlin
dwdrainalarm:rainalarm:home [ geolocation="52.520008,13.404954", interval="300", radius="10" prediction-time="10"]
```

demo.items:

```
Number current_rain_radar "Current Rain Radar" { channel="dwdrainalarm:rainalarm:home:current" }
Number max_rain_radar "Max Rain Radar" { channel="dwdrainalarm:rainalarm:home:maxInRadius" }
Number prediction_radar "Prediction Rain Radar" { channel="dwdrainalarm:rainalarm:home:prediction" }
```
