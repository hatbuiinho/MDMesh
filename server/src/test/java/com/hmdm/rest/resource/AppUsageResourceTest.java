package com.hmdm.rest.resource;

import com.hmdm.rest.json.agent.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class AppUsageResourceTest {
    private DesiredAppUsage policy() {
        DesiredAppUsage p = new DesiredAppUsage(); p.setTimezone("Asia/Ho_Chi_Minh");
        AppRestSchedule s = new AppRestSchedule(); s.setId("sleep"); s.setName("Giờ ngủ");
        s.setDays(Arrays.asList(1,2,3,4,5,6,7)); s.setFrom("22:00"); s.setTo("06:00");
        p.getSchedules().add(s); return p;
    }
    @Test public void scheduleOnlyPolicyIsValid() { assertNull(AppUsageResource.validate(policy())); }
    @Test public void rejectsEmptySelectedApps() {
        DesiredAppUsage p=policy(); p.getSchedules().get(0).setScope("SELECTED");
        assertNotNull(AppUsageResource.validate(p));
    }
    @Test public void rejectsDuplicateIdsAndInvalidWindows() {
        DesiredAppUsage p=policy(); p.getSchedules().add(p.getSchedules().get(0));
        assertNotNull(AppUsageResource.validate(p));
        assertNotNull(AppUsageResource.validateWindow(Arrays.asList(1), "25:00", "06:00"));
        assertNotNull(AppUsageResource.validateWindow(Arrays.asList(1), "06:00", "06:00"));
        assertNotNull(AppUsageResource.validateWindow(Arrays.asList(1,1), "22:00", "06:00"));
        assertNull(AppUsageResource.validateWindow(Arrays.asList(1), "22:00", "06:00"));
    }
}
