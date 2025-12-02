package org.linda.common.enums;

import static org.linda.trident.core.Constant.FULLNODE_NILE;
import static org.linda.trident.core.Constant.FULLNODE_NILE_SOLIDITY;
import static org.lindaa.trident.core.Constant.LINDAGRID_MAIN_NET;
import static org.lindaa.trident.core.Constant.LINDAGRID_MAIN_NET_SOLIDITY;
import static org.linda.trident.core.Constant.LINDAGRID_SHASTA;
import static org.linda.trident.core.Constant.LINDAGRID_SHASTA_SOLIDITY;

import lombok.Getter;
import lombok.Setter;

@Getter
public enum NetType {
  MAIN(
      "https://api.lindagrid.io",
      new Grpc(LINDAGRID_MAIN_NET, LINDAGRID_MAIN_NET_SOLIDITY),
      new GasFree(
          728126428L,
          "TFFAMQLZybALaLb4uxHA9RBE7pxhUAjF3U",
          "https://open.gasfree.io",
          "/linda")
  ),
  NILE("https://nile.lindagrid.io",
      new Grpc(FULLNODE_NILE, FULLNODE_NILE_SOLIDITY),
      new GasFree(
          3448148188L,
          "THQGuFzL87ZqhxkgqYEryRAd7gqFqL5rdc",
          "https://open-test.gasfree.io",
          "/nile")
  ),
  SHASTA(
      "https://api.shasta.lindagrid.io",
      new Grpc(LINDAGRID_SHASTA, LINDAGRID_SHASTA_SOLIDITY),
      new GasFree(
          2494104990L,
          "TSwCtDum13k1PodgNgTWx5be7k1c6eWaNP",
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


