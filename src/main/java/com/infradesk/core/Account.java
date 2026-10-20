package com.infradesk.core;

import java.util.Map;
import java.util.Objects;

/**
 * 클라우드 계정(OCI 테넌시, AWS 계정 등). 비밀값은 갖지 않는다. 자격 증명은 저장소 계층의
 * 비밀값 저장소에 있고 provider 팩토리에 따로 전달된다.
 *
 * @param id          안정적인 로컬 식별자
 * @param displayName UI에 표시되는 이름. 예: "계정 A"
 * @param provider    이 계정이 속한 클라우드
 * @param region      기본 리전 식별자. 예: "ap-chuncheon-1"
 * @param properties  비밀이 아닌 provider별 설정(예: OCI 테넌시/사용자 OCID, fingerprint)
 */
public record Account(String id, String displayName, ProviderType provider, String region,
                      Map<String, String> properties) {

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(region, "region");
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    public String property(String key) {
        return properties.get(key);
    }
}
