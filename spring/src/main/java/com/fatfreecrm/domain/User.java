package com.fatfreecrm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "users")
@DynamicUpdate
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, length = 32)
    private String username;

    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "first_name", length = 32)
    private String firstName;

    @Column(name = "last_name", length = 32)
    private String lastName;

    @Column(name = "title", length = 64)
    private String title;

    @Column(name = "company")
    private String company;

    @Column(name = "alt_email", length = 254)
    private String altEmail;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "mobile", length = 32)
    private String mobile;

    @Column(name = "google", length = 32)
    private String google;

    @Column(name = "encrypted_password", nullable = false)
    private String encryptedPassword;

    @Column(name = "password_salt", nullable = false)
    private String passwordSalt;

    @Column(name = "last_sign_in_at")
    private Instant lastSignInAt;

    @Column(name = "current_sign_in_at")
    private Instant currentSignInAt;

    @Column(name = "last_sign_in_ip")
    private String lastSignInIp;

    @Column(name = "current_sign_in_ip")
    private String currentSignInIp;

    @Column(name = "sign_in_count", nullable = false)
    private int signInCount;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "admin", nullable = false)
    private boolean admin;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "unconfirmed_email", length = 254)
    private String unconfirmedEmail;

    @Column(name = "reset_password_token")
    private String resetPasswordToken;

    @Column(name = "reset_password_sent_at")
    private Instant resetPasswordSentAt;

    @Column(name = "remember_token")
    private String rememberToken;

    @Column(name = "remember_created_at")
    private Instant rememberCreatedAt;

    @Column(name = "authentication_token")
    private String authenticationToken;

    @Column(name = "confirmation_token", length = 255)
    private String confirmationToken;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "confirmation_sent_at")
    private Instant confirmationSentAt;

    @Column(name = "subscribe_to_comment_replies", nullable = false)
    private boolean subscribeToCommentReplies;

    @Column(name = "receive_assigned_notifications", nullable = false)
    private boolean receiveAssignedNotifications;

    @Column(name = "zoom", length = 128)
    private String zoom;

    @Column(name = "teams", length = 128)
    private String teams;

    @Column(name = "signal", length = 128)
    private String signal;

    @Column(name = "instagram", length = 128)
    private String instagram;

    @Column(name = "facebook", length = 128)
    private String facebook;

    @Column(name = "mastodon", length = 128)
    private String mastodon;

    @Column(name = "bluesky", length = 128)
    private String bluesky;

    @Column(name = "twitter", length = 128)
    private String twitter;

    @Column(name = "linkedin", length = 128)
    private String linkedin;

    @Column(name = "blog", length = 128)
    private String blog;

    @ManyToMany
    @JoinTable(
        name = "groups_users",
        joinColumns = @JoinColumn(name = "user_id", columnDefinition = "int4"),
        inverseJoinColumns = @JoinColumn(name = "group_id", columnDefinition = "int4")
    )
    private Set<Group> groups = new HashSet<>();

    @OneToMany(mappedBy = "user")
    private Set<Permission> permissions = new HashSet<>();

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public String getAltEmail() {
        return altEmail;
    }

    public void setAltEmail(String altEmail) {
        this.altEmail = altEmail;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getMobile() {
        return mobile;
    }

    public void setMobile(String mobile) {
        this.mobile = mobile;
    }

    public String getGoogle() {
        return google;
    }

    public void setGoogle(String google) {
        this.google = google;
    }

    public String getEncryptedPassword() {
        return encryptedPassword;
    }

    public void setEncryptedPassword(String encryptedPassword) {
        this.encryptedPassword = encryptedPassword;
    }

    public String getPasswordSalt() {
        return passwordSalt;
    }

    public void setPasswordSalt(String passwordSalt) {
        this.passwordSalt = passwordSalt;
    }

    public String getUnconfirmedEmail() {
        return unconfirmedEmail;
    }

    public void setUnconfirmedEmail(String unconfirmedEmail) {
        this.unconfirmedEmail = unconfirmedEmail;
    }

    public String getResetPasswordToken() {
        return resetPasswordToken;
    }

    public void setResetPasswordToken(String resetPasswordToken) {
        this.resetPasswordToken = resetPasswordToken;
    }

    public Instant getResetPasswordSentAt() {
        return resetPasswordSentAt;
    }

    public void setResetPasswordSentAt(Instant resetPasswordSentAt) {
        this.resetPasswordSentAt = resetPasswordSentAt;
    }

    public String getRememberToken() {
        return rememberToken;
    }

    public void setRememberToken(String rememberToken) {
        this.rememberToken = rememberToken;
    }

    public Instant getRememberCreatedAt() {
        return rememberCreatedAt;
    }

    public void setRememberCreatedAt(Instant rememberCreatedAt) {
        this.rememberCreatedAt = rememberCreatedAt;
    }

    public String getAuthenticationToken() {
        return authenticationToken;
    }

    public void setAuthenticationToken(String authenticationToken) {
        this.authenticationToken = authenticationToken;
    }

    public String getConfirmationToken() {
        return confirmationToken;
    }

    public void setConfirmationToken(String confirmationToken) {
        this.confirmationToken = confirmationToken;
    }

    public Instant getConfirmationSentAt() {
        return confirmationSentAt;
    }

    public void setConfirmationSentAt(Instant confirmationSentAt) {
        this.confirmationSentAt = confirmationSentAt;
    }

    public boolean isSubscribeToCommentReplies() {
        return subscribeToCommentReplies;
    }

    public void setSubscribeToCommentReplies(boolean subscribeToCommentReplies) {
        this.subscribeToCommentReplies = subscribeToCommentReplies;
    }

    public boolean isReceiveAssignedNotifications() {
        return receiveAssignedNotifications;
    }

    public void setReceiveAssignedNotifications(boolean receiveAssignedNotifications) {
        this.receiveAssignedNotifications = receiveAssignedNotifications;
    }

    public String getZoom() {
        return zoom;
    }

    public void setZoom(String zoom) {
        this.zoom = zoom;
    }

    public String getTeams() {
        return teams;
    }

    public void setTeams(String teams) {
        this.teams = teams;
    }

    public String getSignal() {
        return signal;
    }

    public void setSignal(String signal) {
        this.signal = signal;
    }

    public String getInstagram() {
        return instagram;
    }

    public void setInstagram(String instagram) {
        this.instagram = instagram;
    }

    public String getFacebook() {
        return facebook;
    }

    public void setFacebook(String facebook) {
        this.facebook = facebook;
    }

    public String getMastodon() {
        return mastodon;
    }

    public void setMastodon(String mastodon) {
        this.mastodon = mastodon;
    }

    public String getBluesky() {
        return bluesky;
    }

    public void setBluesky(String bluesky) {
        this.bluesky = bluesky;
    }

    public String getTwitter() {
        return twitter;
    }

    public void setTwitter(String twitter) {
        this.twitter = twitter;
    }

    public String getLinkedin() {
        return linkedin;
    }

    public void setLinkedin(String linkedin) {
        this.linkedin = linkedin;
    }

    public String getBlog() {
        return blog;
    }

    public void setBlog(String blog) {
        this.blog = blog;
    }

    public boolean isAdmin() {
        return admin;
    }

    public void setAdmin(boolean admin) {
        this.admin = admin;
    }

    public Instant getSuspendedAt() {
        return suspendedAt;
    }

    public void setSuspendedAt(Instant suspendedAt) {
        this.suspendedAt = suspendedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public int getSignInCount() {
        return signInCount;
    }

    public void setSignInCount(int signInCount) {
        this.signInCount = signInCount;
    }

    public Instant getCurrentSignInAt() {
        return currentSignInAt;
    }

    public void setCurrentSignInAt(Instant currentSignInAt) {
        this.currentSignInAt = currentSignInAt;
    }

    public Instant getLastSignInAt() {
        return lastSignInAt;
    }

    public void setLastSignInAt(Instant lastSignInAt) {
        this.lastSignInAt = lastSignInAt;
    }

    public String getCurrentSignInIp() {
        return currentSignInIp;
    }

    public void setCurrentSignInIp(String currentSignInIp) {
        this.currentSignInIp = currentSignInIp;
    }

    public String getLastSignInIp() {
        return lastSignInIp;
    }

    public void setLastSignInIp(String lastSignInIp) {
        this.lastSignInIp = lastSignInIp;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Set<Group> getGroups() {
        return Set.copyOf(groups);
    }

    public Set<Permission> getPermissions() {
        return Set.copyOf(permissions);
    }
}
