package com.hmdm.rest.json.agent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import static org.junit.Assert.*;
public class RestScheduleContractTest {
    @Test public void roundTripsScheduleOnlyPolicyAndKeepsOldDefaults() throws Exception {
        ObjectMapper json=new ObjectMapper();
        DesiredAppUsage old=json.readValue("{\"timezone\":\"UTC\",\"rules\":[]}", DesiredAppUsage.class);
        assertTrue(old.getSchedules().isEmpty());
        DesiredAppUsage policy=json.readValue("{\"timezone\":\"Asia/Ho_Chi_Minh\",\"rules\":[],\"schedules\":[{\"id\":\"sleep\",\"name\":\"Giờ ngủ\",\"days\":[1],\"from\":\"22:00\",\"to\":\"06:00\"}]}", DesiredAppUsage.class);
        DesiredAppUsage copy=json.readValue(json.writeValueAsString(policy), DesiredAppUsage.class);
        assertEquals("sleep", copy.getSchedules().get(0).getId());
        assertTrue(copy.getSchedules().get(0).isEnabled());
        assertEquals("ALL", copy.getSchedules().get(0).getScope());
    }
}
