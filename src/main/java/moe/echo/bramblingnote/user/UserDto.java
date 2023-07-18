package moe.echo.bramblingnote.user;

import com.fasterxml.jackson.annotation.JsonView;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Date;
import java.util.UUID;

@Getter
@Setter
public class UserDto implements Serializable {
    @JsonView(View.ViewOnly.class)
    private UUID id;

    @JsonView(View.Editable.class)
    @NotBlank
    private String email;

    @JsonView(View.Editable.class)
    @NotBlank
    private String name;

    @JsonView(View.EditOnly.class)
    @NotBlank
    private String password;

    @JsonView(View.Internal.class)
    @NotBlank
    private String passwordHash;

    @JsonView(View.ViewOnly.class)
    @NotBlank
    private Boolean verified;

    @JsonView(View.Internal.class)
    private String verificationCode;

    @JsonView(View.ViewOnly.class)
    private Date lastVerificationEmail;
}
