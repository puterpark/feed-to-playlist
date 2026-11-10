package us.puter.feedtoplaylist.global.util;

import com.github.f4b6a3.uuid.UuidCreator;

import java.util.UUID;

public class UuidUtils {

    /**
     * 시간순 정렬 가능한 UUID(v7) 생성
     */
    public static UUID next() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}
