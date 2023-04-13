package moe.echo.bramblingnote.user;

import org.springframework.data.repository.CrudRepository;

import java.util.Optional;
import java.util.UUID;

public interface Repository extends CrudRepository<UserEntity, UUID> {
    boolean existsByEmail(String email);
}
