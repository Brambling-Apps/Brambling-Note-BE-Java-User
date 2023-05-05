package moe.echo.bramblingnote.user;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Date;
import java.util.UUID;

@Getter
@Setter
public class UserForReturn implements Serializable {
    private UUID id;

    private String email;

    private String name;

    private Boolean verified = false;

    private Date lastVerificationEmail;
}
