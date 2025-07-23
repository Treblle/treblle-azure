package com.treblle.azure.dto;

import com.fasterxml.jackson.annotation.JsonGetter;

public class Server {

  private String ip;
  private String timezone;
  private String signature;
  private String protocol;
  private OperatingSystem os;
  private String encoding;
  private static final String SOFTWARE = "Azure";

  public String getIp() {
    return ip;
  }

  public void setIp(String ip) {
    this.ip = ip;
  }

  public String getTimezone() {
    return timezone;
  }

  public void setTimezone(String timezone) {
    this.timezone = timezone;
  }

  @JsonGetter("software")
  public String getSoftware() {
    return SOFTWARE;
  }

  public String getSignature() {
    return signature;
  }

  public void setSignature(String signature) {
    this.signature = signature;
  }

  public String getProtocol() {
    return protocol;
  }

  public void setProtocol(String protocol) {
    this.protocol = protocol;
  }

  public OperatingSystem getOs() {
    return os;
  }

  public void setOs(OperatingSystem os) {
    this.os = os;
  }

  public String getEncoding() {
    return encoding;
  }

  public void setEncoding(String encoding) {
    this.encoding = encoding;
  }
}
