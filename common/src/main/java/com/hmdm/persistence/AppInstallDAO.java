package com.hmdm.persistence;
import com.hmdm.persistence.mapper.AppInstallMapper;
import com.hmdm.persistence.domain.AppInstallRequest;
import javax.inject.*;
import java.util.*;
@Singleton public class AppInstallDAO {
    private final AppInstallMapper mapper;
    @Inject public AppInstallDAO(AppInstallMapper mapper){this.mapper=mapper;}
    @org.mybatis.guice.transactional.Transactional public int submit(AppInstallRequest row){
        mapper.lockDevice(row.getDeviceNumber());
        mapper.invalidate(row.getDeviceNumber(),row.getConfigurationId());
        if(mapper.open(row)>0)return 0;
        if(mapper.recentCount(row.getDeviceNumber(),System.currentTimeMillis()-3600000)>=30)return -1;
        return mapper.insert(row);
    }
    public List<AppInstallRequest> list(int customer,int offset){return mapper.list(customer,offset);}
    public List<AppInstallRequest> deviceRequests(String device){return mapper.deviceRequests(device);}
    public List<AppInstallRequest> notificationCandidates(int customer){return mapper.notificationCandidates(customer);}
    public AppInstallRequest get(int id){return mapper.get(id);}
    @org.mybatis.guice.transactional.Transactional public int decide(AppInstallRequest row,String expected){int changed=mapper.decide(row,expected);if(changed==1)mapper.audit(row);return changed;}
    public List<Map<String,Object>> history(int id){return mapper.history(id);}
    public void installed(int id,String device){mapper.installed(id,device,System.currentTimeMillis());}
    public void invalidate(String device,int config){mapper.invalidate(device,config);}
    public List<String> grants(String device,int config){return mapper.grants(device,config);}
}
