package org.openhab.binding.dwdrainalarm.internal.radolan;

import com.bitplan.geo.DPoint;
import com.bitplan.geo.IPoint;
import cs.fau.de.since.radolan.Composite;
import cs.fau.de.since.radolan.Translate;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.text.MessageFormat;

public class RadolanReader {
    private final Logger logger = LoggerFactory.getLogger(RadolanReader.class);
    private final String RADOLAN_WX_PRODUCT_URL = "https://opendata.dwd.de/weather/radar/composit/wx/raa01-wx_10000-latest-dwd---bin";
    private final String RADOLAN_WN_PRODUCT_URL = "https://opendata.dwd.de/weather/radar/composit/wn/WN_LATEST.tar.bz2";

    private double latitude;
    private double longitude;
    private int predictionTime = 10;
    private Composite compositeWX;
    private Composite compositeWN;

    public RadolanReader() {
        try { init(); } catch (RadolanRetrieveException e) {
            logger.debug("Cannot initialize Radolan reader!", e);
        }
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

    private void init() throws RadolanRetrieveException {
        InputStream inputStream = null;
        BZip2CompressorInputStream gzipIn = null;
        ByteArrayOutputStream bout = null;
        BufferedOutputStream bufout = null;
        ByteArrayInputStream bin = null;
        ByteArrayInputStream binInit = null;
        try {
            URL url = new URL(RADOLAN_WN_PRODUCT_URL);
            URLConnection con = url.openConnection();
            con.setConnectTimeout(15 * 1000);
            con.setReadTimeout(15 * 1000);
            inputStream = con.getInputStream();
            byte[] lbytes = IOUtils.toByteArray(inputStream);
            binInit = new ByteArrayInputStream(lbytes);
            gzipIn = new BZip2CompressorInputStream(binInit);
            int BUFFER_SIZE = 5000;
            byte[] buffer = new byte[BUFFER_SIZE];
            bout = new ByteArrayOutputStream();
            bufout = new BufferedOutputStream(bout, BUFFER_SIZE);
//            int count = 0;
//            while ((count = gzipIn.read(buffer, 0, BUFFER_SIZE)) != -1) {
//                bufout.write(buffer, 0, count);
//            }
            try (TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn)) {
                TarArchiveEntry entry;
                while ((entry = (TarArchiveEntry) tarIn.getNextEntry()) != null) {
                    String[] parts = StringUtils.split(entry.getName(), "_");
                    int filePrediction = Integer.parseInt(parts[1]);
                    if (filePrediction == predictionTime) {
                        int count = 0;
                        while ((count = tarIn.read(buffer, 0, BUFFER_SIZE)) != -1) {
                            bufout.write(buffer, 0, count);
                        }
                        break;
                    }
                }
            }
            bufout.close();
            bout.close();
            bin = new ByteArrayInputStream(bout.toByteArray());
            compositeWN = new Composite(bin);
        } catch (Throwable throwable) {
            logger.debug("Error getting rain data for WN from DWD!", throwable);
            throw new RadolanRetrieveException(throwable.getMessage(), throwable);
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
            if (binInit != null) {
                try { binInit.close(); } catch (Exception e) {
                    logger.debug("Error closing stream", e);
                }
            }
            if (inputStream != null)
                try { inputStream.close(); } catch (Exception e) {
                    logger.debug("Error closing stream", e);
                }
        }
        InputStream inputStream2 = null;
        try {
            URL url = new URL(RADOLAN_WX_PRODUCT_URL);
            URLConnection con = url.openConnection();
            con.setConnectTimeout(15 * 1000);
            con.setReadTimeout(15 * 1000);
            inputStream2 = con.getInputStream();

            compositeWX = new Composite(inputStream2);
        } catch (Throwable throwable) {
            logger.debug("Error getting rain data for WX from DWD!", throwable);
            throw new RadolanRetrieveException(throwable.getMessage(), throwable);
        } finally {
            if (inputStream2 != null)
                try { inputStream2.close(); } catch (Exception e) {
                    logger.debug("Error closing stream", e);
                }
        }
    }

    public void refresh() throws RadolanRetrieveException {
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