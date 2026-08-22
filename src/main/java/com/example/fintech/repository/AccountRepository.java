package com.example.fintech.repository;

import com.example.fintech.model.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByAccountRef(String accountRef);

    // Locks accounts sorted by ref (the query's ORDER BY), so two transfers
    // touching the same accounts in opposite request-order still acquire
    // locks in the same global order and can't deadlock on each other.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.accountRef in :refs order by a.accountRef")
    List<Account> lockByAccountRefs(@Param("refs") Collection<String> refs);
}
