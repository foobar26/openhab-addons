package org.openhab.binding.dwdrainalarm.internal.radolan;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;

public class RadolanReaderTest {
    private RadolanReader radolanReader;

    @Before
    public void setUp() {
        radolanReader = new RadolanReader();
//        radolanReader.setUrlForProduct("https://opendata.dwd.de/weather/radar/composit/wx/raa01-wx_10000-2007141450-dwd---bin", false);
        radolanReader.setUrlForProduct("https://opendata.dwd.de/weather/radar/composit/wx/raa01-wx_10000-latest-dwd---bin", false);
    }

    @Test
    public void testUpdateCurrent() {
//        radolanReader.initializePosition(50.86666666666667, 6.1);
        radolanReader.initializePosition(50.87483608732879, 6.099723069648566);
        Float value = radolanReader.updateCurrent();
        System.out.println("Value: " + value);
        assertNotNull("Rain value should not be null!", value);
    }

    @Test
    public void testGetHighestValueWithinRadius() {
        radolanReader.initializePosition(50.87483608732879, 6.099723069648566);
        Float value = radolanReader.getMaxRainWithinRadius(10);
        System.out.println("Value: " + value);
        assertNotNull("Rain value should not be null!", value);
    }

}
