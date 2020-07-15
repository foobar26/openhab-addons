package org.openhab.binding.dwdrainalarm.internal.radolan;

import com.bitplan.geo.DPoint;
import com.bitplan.geo.IPoint;
import cs.fau.de.since.radolan.Composite;
import cs.fau.de.since.radolan.Translate;
import gov.nasa.worldwind.geom.Angle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RadolanReader {
    private final Logger logger = LoggerFactory.getLogger(RadolanReader.class);

    private double latitude;
    private double longitude;
    private String URL;
    private Composite comp;

    public void setUrlForProduct(String URL, boolean useCache) {
        this.URL = URL;
        Composite.useCache = useCache;
        try {
            comp = new Composite(this.URL);
        } catch (Throwable throwable) {
            logger.error("Error getting rain data from DWD!", throwable);
        }
    }

    public void refresh() {
        try {
            comp = new Composite(this.URL);
        } catch (Throwable throwable) {
            logger.error("Error getting rain data from DWD!", throwable);
        }
    }


    public void initializePosition(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public Float updateCurrent() {
        logger.debug("Updating rain data!");
        DPoint location = new DPoint(this.latitude, this.longitude);
        float valueNew = this.getValueAtCoord(comp, location);
        return valueNew;
    }

    public Float getMaxRainWithinRadius(int kmRadius) {
        DPoint location = new DPoint(this.latitude, this.longitude);
        return this.getHighestValueWithinRadius(this.comp, location, kmRadius);
    }

    /**
     * get the value at the given coordinate
     * @param coord
     * @return - the value
     */
    private float getValueAtCoord(Composite comp, DPoint coord) {
        DPoint gdp = comp.translateLatLonToGrid(coord.x, coord.y);
        IPoint gp = new IPoint(gdp);
        float rain = comp.getValue(gp.x, gp.y);
        return rain;
    }

    private float getHighestValueWithinRadius(Composite comp, DPoint location, int kmRadius) {
        float returnValue = -32.5F;
        for (int y = 0; y<comp.PlainData.length;y++) {
            for (int x = 0; x < comp.PlainData[y].length; x++) {
                DPoint latlon = comp.translateGridToLatLon(new DPoint(x, y));
                double distInKM = Translate.haversine(location.x, location.y, latlon.x, latlon.y);
                float value = getValueAtCoord(comp, latlon);
                if (distInKM < kmRadius && value > returnValue) {
                    returnValue = value;
                }
            }
        }
        return returnValue;
    }
}