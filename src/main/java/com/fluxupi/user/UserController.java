package com.fluxupi.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/users")
@Tag(name = "Users", description = "Simulated borrowers and their mock VPAs")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    @Operation(summary = "Register a simulated user")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterUserRequest request,
                                                 UriComponentsBuilder uriBuilder) {
        User user = userService.register(request.fullName(), request.vpa(), request.declaredMonthlyIncome());
        var location = uriBuilder.path("/users/{id}").buildAndExpand(user.getId()).toUri();
        return ResponseEntity.created(location).body(UserResponse.from(user));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a user by id")
    @ResponseStatus(HttpStatus.OK)
    public UserResponse get(@PathVariable UUID id) {
        return UserResponse.from(userService.get(id));
    }

    public record RegisterUserRequest(@NotBlank String fullName,
                                      @NotBlank String vpa,
                                      @NotNull @PositiveOrZero BigDecimal declaredMonthlyIncome) {
    }

    public record UserResponse(UUID id, String fullName, String vpa,
                               BigDecimal declaredMonthlyIncome, Instant createdAt) {
        static UserResponse from(User user) {
            return new UserResponse(user.getId(), user.getFullName(), user.getVpa(),
                    user.getDeclaredMonthlyIncome(), user.getCreatedAt());
        }
    }
}
