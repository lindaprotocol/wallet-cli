package org.linda.common.enums;

import lombok.Getter;
import lombok.Setter;

@Getter
public enum NetType {
  MAIN(
      "https://api.lindagrid.io",
      new Grpc("grpc.lindagrid.io:50051", "grpc.lindagrid.io:50052"),
      new GasFree(
          728126428L,
          "LQVucZGfuXHSfyDbVpcjuHYrRFafKYDiZn",
          "https://open.gasfree.io",
          "/linda")
  ),
  NILE("https://nile.lindagrid.io",
      new Grpc("grpc.nile.lindagrid.io:50051", "grpc.nile.lindagrid.io:50061"),
      new GasFree(
          3448148188L,
          "LSf2AQvS43gwobPDRQaSjHYFR7TDmRui9x",
          "https://open-test.gasfree.io",
          "/nile")
  ),
  SHASTA(
      "https://api.shasta.lindagrid.io",
      new Grpc("grpc.shasta.lindagrid.io:50051", "grpc.shasta.lindagrid.io:50052"),
      new GasFree(
          2494104990L,
          "LcBx9Nqrvys7VSGCxYo6hwyGRAda3ibnrQ",
          "https://open-test.gasfree.io",
          "/shasta")
  ),
  CUSTOM(null, null, null);

  private final String http;
  private final Grpc grpc;
  private final GasFree gasFree;

  NetType(String http, Grpc grpc, GasFree gasFree) {
    this.http = http;
    this.grpc = grpc;
    this.gasFree = gasFree;
  }

  @Setter
  @Getter
  public static class Grpc {
    public Grpc(String fullNode, String solidityNode) {
      this.fullNode = fullNode;
      this.solidityNode = solidityNode;
    }

    private String fullNode;
    private String solidityNode;
  }

  @Setter
  @Getter
  public static class GasFree {
    private long chainId;
    private String verifyingContract;
    private String httpUrl;
    private String apiPrefix;

    public GasFree(long chainId, String verifyingContract, String httpUrl, String apiPrefix) {
      this.chainId = chainId;
      this.verifyingContract = verifyingContract;
      this.httpUrl = httpUrl;
      this.apiPrefix = apiPrefix;
    }


  }
}


