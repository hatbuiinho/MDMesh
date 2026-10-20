package com.hmdm.rest.json.agent;
import lombok.Getter;
import lombok.Setter;
import java.util.*;
@Getter @Setter
public class NetworkRule {
    private String id;
    private String name;
    private String mode = "BLOCKLIST";
    private String scope = "SELECTED";
    private List<String> packages = new ArrayList<>();
    private List<String> domains = new ArrayList<>();
}
