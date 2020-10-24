package org.openhab.binding.dwdrainalarm.internal.radolan;

import com.bitplan.geo.DPoint;
import com.bitplan.geo.IPoint;
import cs.fau.de.since.radolan.Composite;
import cs.fau.de.since.radolan.Translate;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.text.MessageFormat;

public class RadolanReader {
    private final Logger logger = LoggerFactory.getLogger(RadolanReader.class);
    private final String RADOLAN_WX_PRODUCT_URL = "https://opendata.dwd.de/weather/radar/composit/wx/raa01-wx_10000-latest-dwd---bin";
    private final String RADOLAN_WN_PRODUCT_URL = "https://opendata.dwd.de/weather/radar/composit/wn/WN_LATEST_0{0}.bz2";

    private double latitude;
    private double longitude;
    private int predictionTime = 10;
    private Composite compositeWX;
    private Composite compositeWN;

    public RadolanReader() {
        init();
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public void setPosition(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public void setPredictionTime(int predictionTime) {
        this.predictionTime = predictionTime;
    }

    private void init() {
        InputStream inputStream = null;
        BZip2CompressorInputStream gzipIn = null;
        ByteArrayOutputStream bout = null;
        BufferedOutputStream bufout = null;
        ByteArrayInputStream bin = null;
        try {
            inputStream = new URL(MessageFormat.format(RADOLAN_WN_PRODUCT_URL, predictionTime)).openStream();
            gzipIn = new BZip2CompressorInputStream(inputStream);
            int BUFFER_SIZE = 5000;
            byte[] buffer = new byte[BUFFER_SIZE];
            bout = new ByteArrayOutputStream();
            bufout = new BufferedOutputStream(bout, BUFFER_SIZE);
            int count = 0;
            while ((count = gzipIn.read(buffer, 0, BUFFER_SIZE)) != -1) {
                bufout.write(buffer, 0, count);
            }
            bufout.close();
            bout.close();
            bin = new ByteArrayInputStream(bout.toByteArray());
            compositeWN = new Composite(bin);
        } catch (Throwable throwable) {
            logger.error("Error getting rain data from DWD!", throwable);
        } finally {
            if (bin != null)
                try { bin.close(); } catch (Exception e) {
                    logger.debug("Error closing stream", e);
                }
            if (bufout != null)
                try { bufout.close(); } catch (Exception e) {
                    logger.debug("Error closing stream", e);
                }
            if (bout != null)
                try { bout.close(); } catch (Exception e) {
                    logger.debug("Error closing stream", e);
                }
            if (gzipIn != null)
                try { gzipIn.close(); } catch (Exception e) {
                    logger.debug("Error closing stream", e);
                }
            if (inputStream != null)
                try { inputStream.close(); } catch (Exception e) {
                    logger.debug("Error closing stream", e);
                }
        }
        try {
            compositeWX = new Composite(RADOLAN_WX_PRODUCT_URL);
        } catch (Throwable throwable) {
            logger.error("Error getting rain data from DWD!", throwable);
        }
    }

    public void refresh() {
        init();
    }

    public Float getCurrent() {
        DPoint location = new DPoint(this.latitude, this.longitude);
        float valueNew = this.getValueAtCoord(compositeWX, location);
        return valueNew;
    }

    public Float getPrediction() {
        DPoint location = new DPoint(this.latitude, this.longitude);
        float valueNew = this.getValueAtCoord(compositeWN, location);
        return valueNew;
    }

    public Float getMaxRainWithinRadius(int kmRadius) {
        DPoint location = new DPoint(this.latitude, this.longitude);
        return this.getHighestValueWithinRadius(this.compositeWX, location, kmRadius);
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