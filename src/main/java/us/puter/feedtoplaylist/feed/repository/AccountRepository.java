package us.puter.feedtoplaylist.feed.repository;

import generated.jooq.obj.tables.daos.AccountDao;
import generated.jooq.obj.tables.pojos.Account;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import us.puter.feedtoplaylist.global.util.UuidUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static generated.jooq.obj.Tables.ACCOUNT;

@Repository
public class AccountRepository extends AccountDao {

    private final DSLContext dsl;

    public AccountRepository(DSLContext dsl) {
        super(dsl.configuration());
        this.dsl = dsl;
    }

    public List<Account> findActive() {
        return dsl.selectFrom(ACCOUNT)
                .where(ACCOUNT.STATUS.eq("ACTIVE"))
                .orderBy(ACCOUNT.ID)
                .fetchInto(Account.class);
    }

    /**
     * 재연결 시 refresh token 을 갱신하고 ACTIVE 로 복구한다.
     */
    public Account upsert(String googleSub, String email, String refreshTokenEnc) {
        return dsl.insertInto(ACCOUNT)
                .set(ACCOUNT.ID, UuidUtils.next())
                .set(ACCOUNT.GOOGLE_SUB, googleSub)
                .set(ACCOUNT.EMAIL, email)
                .set(ACCOUNT.REFRESH_TOKEN_ENC, refreshTokenEnc)
                .set(ACCOUNT.STATUS, "ACTIVE")
                .onConflict(ACCOUNT.GOOGLE_SUB)
                .doUpdate()
                .set(ACCOUNT.EMAIL, DSL.excluded(ACCOUNT.EMAIL))
                .set(ACCOUNT.REFRESH_TOKEN_ENC, DSL.excluded(ACCOUNT.REFRESH_TOKEN_ENC))
                .set(ACCOUNT.STATUS, "ACTIVE")
                .set(ACCOUNT.UPDATED_AT, LocalDateTime.now())
                .returning()
                .fetchSingle()
                .into(Account.class);
    }

    public void updatePlaylistId(UUID id, String playlistId) {
        dsl.update(ACCOUNT)
                .set(ACCOUNT.PLAYLIST_ID, playlistId)
                .set(ACCOUNT.UPDATED_AT, LocalDateTime.now())
                .where(ACCOUNT.ID.eq(id))
                .execute();
    }

    public void updateSubscriptionsSyncedAt(UUID id, Instant at) {
        dsl.update(ACCOUNT)
                .set(ACCOUNT.SUBSCRIPTIONS_SYNCED_AT, LocalDateTime.ofInstant(at, ZoneId.systemDefault()))
                .set(ACCOUNT.UPDATED_AT, LocalDateTime.now())
                .where(ACCOUNT.ID.eq(id))
                .execute();
    }

    public void markRevoked(UUID id) {
        dsl.update(ACCOUNT)
                .set(ACCOUNT.STATUS, "REVOKED")
                .set(ACCOUNT.UPDATED_AT, LocalDateTime.now())
                .where(ACCOUNT.ID.eq(id))
                .execute();
    }
}
