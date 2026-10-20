package com.hmdm.persistence;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.mapper.NetworkAccessMapper;
import com.hmdm.persistence.domain.NetworkAccessRequest;
import com.hmdm.rest.json.agent.*;
import com.hmdm.util.CryptoUtil;
import javax.inject.*;
import java.util.*;
@Singleton
public class NetworkAccessDAO {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final NetworkAccessMapper mapper;
    @Inject public NetworkAccessDAO(NetworkAccessMapper mapper) { this.mapper=mapper; }
    public List<NetworkRule> rules(int id) {
        try { String s=mapper.rules(id); return s==null?new ArrayList<>():JSON.readValue(s,new TypeReference<List<NetworkRule>>(){}); }
        catch(Exception e) { throw new IllegalStateException("Invalid network rules",e); }
    }
    public void save(int id,List<NetworkRule> rules) { mapper.save(id,encode(rules)); }
    public static String encode(Object value) { try{return JSON.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException(e);} }
    public static List<String> packages(String value) {
        try{return JSON.readValue(value,new TypeReference<List<String>>(){});}catch(Exception e){throw new IllegalStateException(e);}
    }
    public DesiredWebAccess decorate(int id,String device,DesiredWebAccess legacy) {
        List<NetworkRule> rules=rules(id);
        if(legacy==null && rules.isEmpty()) {
            Map<String,Object> version=mapper.version(device);
            if(version!=null)mapper.advanceVersion(device,CryptoUtil.getSHA256String(id+":OFF"));
            return null;
        }
        DesiredWebAccess out=new DesiredWebAccess();
        out.setMode(legacy==null?"OFF":legacy.getMode());out.setScope(legacy==null?"ALL":legacy.getScope());
        out.setPackages(legacy==null?new ArrayList<>():legacy.getPackages());out.setDomains(legacy==null?new ArrayList<>():legacy.getDomains());
        out.setRules(rules);
        // Hash only network policy, not grants or unrelated config fields. Configuration ID is also bound in SQL.
        String fingerprint=CryptoUtil.getSHA256String(id+":"+encode(out));
        Map<String,Object> version=mapper.version(device);
        if(version==null){mapper.initVersion(device,fingerprint);version=mapper.version(device);}
        if(!fingerprint.equals(version.get("fingerprint"))){mapper.advanceVersion(device,fingerprint);version=mapper.version(device);}
        out.setPolicyHash(CryptoUtil.getSHA256String(fingerprint+":"+version.get("generation")));
        for(NetworkAccessRequest r:mapper.grants(device,id,out.getPolicyHash(),System.currentTimeMillis())) {
            NetworkGrant g=new NetworkGrant();g.setRequestId(r.getId());g.setDomain(r.getDomain());g.setPackages(packages(r.getGrantPackages()));g.setExpiresAt(r.getExpiresAt());out.getGrants().add(g);
        }
        return out;
    }
    public List<NetworkAccessRequest> list(int customer,String device){return list(customer,device,0);}
    public List<NetworkAccessRequest> list(int customer,String device,int offset){return mapper.list(customer,device,offset);}
    public List<NetworkAccessRequest> deviceRequests(String device){return mapper.deviceRequests(device);}
    public NetworkAccessRequest get(int id){return mapper.get(id);}
    public int insert(NetworkAccessRequest row){return mapper.insert(row);}
    @org.mybatis.guice.transactional.Transactional
    public int submit(NetworkAccessRequest row) {
        mapper.lockDevice(row.getDeviceNumber());
        if(mapper.pending(row)>0)return 0;
        if(mapper.recentCount(row.getDeviceNumber(),System.currentTimeMillis()-3600000)>=30)return -1;
        return mapper.insert(row);
    }
    @org.mybatis.guice.transactional.Transactional
    public int decide(NetworkAccessRequest row,String expected){int changed=mapper.decide(row,expected);if(changed==1)mapper.audit(row);return changed;}
    public List<Map<String,Object>> auditTrail(int id){return mapper.auditTrail(id);}
}
