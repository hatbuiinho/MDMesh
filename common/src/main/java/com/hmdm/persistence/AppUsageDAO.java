package com.hmdm.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.domain.*;
import com.hmdm.persistence.mapper.AppUsageMapper;
import com.hmdm.rest.json.agent.*;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.mybatis.guice.transactional.Transactional;
import java.util.*;

@Singleton
public class AppUsageDAO {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AppUsageMapper mapper;
    @Inject public AppUsageDAO(AppUsageMapper mapper) { this.mapper = mapper; }

    public DesiredAppUsage desired(int configurationId) {
        List<AppUsagePolicyRow> rows = mapper.policies(configurationId);
        if (rows.isEmpty()) return null;
        DesiredAppUsage out = new DesiredAppUsage();
        out.setTimezone(rows.get(0).getTimezone());
        for (AppUsagePolicyRow row : rows) {
            AppUsageRule r = new AppUsageRule();
            r.setId(row.getId()); r.setPackageName(row.getPackageName()); r.setDailyLimitMinutes(row.getDailyLimitMinutes());
            r.setWarningMinutes(row.getWarningMinutes()); r.setAction(row.getAction()); r.setEnabled(row.getEnabled());
            try { r.setAllowedWindows(JSON.readValue(row.getAllowedWindows(), new TypeReference<List<AppUsageWindow>>(){})); }
            catch (Exception ignored) { r.setAllowedWindows(Collections.<AppUsageWindow>emptyList()); }
            out.getRules().add(r);
        }
        return out;
    }

    public DesiredAppUsage desiredForDevice(int configurationId, String deviceNumber) {
        DesiredAppUsage out = desired(configurationId);
        if (out == null) return null;
        for (AppUsageOverride row : overrides(configurationId)) {
            if (!deviceNumber.equals(row.getDeviceNumber())) continue;
            AppUsageGrant grant = new AppUsageGrant();
            grant.setPackageName(row.getPackageName()); grant.setExtraMinutes(row.getExtraMinutes()); grant.setExpiresAt(row.getExpiresAt());
            out.getOverrides().add(grant);
        }
        return out;
    }

    @Transactional
    public void replace(int configurationId, DesiredAppUsage desired) {
        mapper.deletePolicies(configurationId);
        if (desired == null || desired.getRules() == null) return;
        for (AppUsageRule r : desired.getRules()) {
            AppUsagePolicyRow row = new AppUsagePolicyRow();
            row.setConfigurationId(configurationId); row.setTimezone(desired.getTimezone()); row.setPackageName(r.getPackageName());
            row.setDailyLimitMinutes(r.getDailyLimitMinutes()); row.setWarningMinutes(r.getWarningMinutes()); row.setAction(r.getAction());
            row.setEnabled(r.getEnabled());
            try { row.setAllowedWindows(JSON.writeValueAsString(r.getAllowedWindows())); } catch (Exception e) { throw new IllegalArgumentException(e); }
            mapper.insertPolicy(row);
        }
    }
    public void upsert(DeviceAppUsageDaily row) { mapper.upsertDaily(row); }
    public List<DeviceAppUsageDaily> report(int customerId, Integer configurationId, String from, String to) { return mapper.report(customerId, configurationId, from, to); }
    public List<DeviceAppUsageDaily> reportDevice(String deviceNumber, String from, String to) { return mapper.reportDevice(deviceNumber, from, to); }
    public void override(AppUsageOverride row) { mapper.upsertOverride(row); }
    public List<AppUsageOverride> overrides(int configurationId) { return mapper.activeOverrides(configurationId, System.currentTimeMillis()); }
}
