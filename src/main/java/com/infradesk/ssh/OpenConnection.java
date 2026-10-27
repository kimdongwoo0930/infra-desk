package com.infradesk.ssh;

import java.time.Instant;

/**
 * 접속 풀이 지금 열어 두고 있는 SSH 연결 하나의 상태 (읽기 전용 정보). 상태 막대의 "SSH 연결 3 · 조회 2"와 설정의
 * "지금 열린 SSH 연결" 표가 쓴다. 터미널 탭은 풀 밖의 연결이라 여기에 나오지 않는다.
 *
 * @param serverId 이 연결이 속한 서버
 * @param purpose  연결의 용도(조회, 명령, 파일, 전송)
 * @param openedAt 연결을 맺은 시각
 * @param lastUsed 마지막으로 쓴 시각
 * @param active   지금 이 연결을 빌려 쓰는 곳의 수(0이면 놀고 있다)
 */
public record OpenConnection(String serverId, Purpose purpose, Instant openedAt, Instant lastUsed, int active) {
}
