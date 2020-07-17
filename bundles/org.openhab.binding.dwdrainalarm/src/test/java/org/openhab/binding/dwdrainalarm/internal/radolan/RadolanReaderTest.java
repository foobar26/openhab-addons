package org.openhab.binding.dwdrainalarm.internal.radolan;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;

public class RadolanReaderTest {
    private RadolanReader radolanReader;

    @Before
    public void setUp() {
        radolanReader = new RadolanReader();
        radolanReader.setPosition(50.87483608732879, 6.099723069648566);
    }

    @Test
    public void testUpdateCurrent() {
        Float value = radolanReader.getCurrent();
        System.out.println("Value: " + value);
        assertNotNull("Rain value should not be null!", value);
    }

    @Test
    public void testGetHighestValueWithinRadius() {
        Float value = radolanReader.getMaxRainWithinRadius(10);
        System.out.println("Value: " + value);
        assertNotNull("Rain value should not be null!", value);
    }

    @Test
    public void testPredictionValue() {
        Float value = radolanReader.getMaxRainWithinRadius(10);
        System.out.println("Value: " + value);
        assertNotNull("Rain value should not be null!", value);
    }

}
