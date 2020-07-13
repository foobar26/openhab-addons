package org.openhab.binding.dwdrainalarm.internal.radolan;

import com.bitplan.geo.DPoint;
import cs.fau.de.since.radolan.Composite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RadolanReader {
    private final Logger logger = LoggerFactory.getLogger(RadolanReader.class);

    private double latitude;
    private double longitude;

    public void initializePosition(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
        Composite.useCache = false;
    }

    public void updateCurrent() {
        logger.debug("Updating rain data!");
        try {
            Composite comp = new Composite("https://opendata.dwd.de/weather/radar/composit/wx/raa01-wx_10000-latest-dwd---bin");
            DPoint location = comp.translateLatLonToGrid(latitude, longitude);
            float valueNew = comp.getValueAtCoord(location);
        } catch (Throwable throwable) {
            logger.error("Error getting rain data from DWD!", throwable);
        }
    }
}