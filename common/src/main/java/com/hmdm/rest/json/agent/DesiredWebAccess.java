package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;
import java.util.List;

@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class DesiredWebAccess {
    private String policyHash;
    private List<NetworkRule> rules = new java.util.ArrayList<>();
    private List<NetworkGrant> grants = new java.util.ArrayList<>();
    private String mode;
    private List<String> domains;
    private String scope;
    private List<String> packages;
}
