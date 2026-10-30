package com.jihajoor.rating.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jihajoor.rating.service.ReviewException;
import com.jihajoor.rating.service.ReviewExperienceService;
import com.jihajoor.rating.service.ReviewExperienceService.PromptView;
import com.jihajoor.rating.service.ReviewExperienceService.PublicReviews;
import com.jihajoor.rating.service.ReviewExperienceService.ReviewView;

/**
 * Reviews for the apps:
 * <ul>
 *   <li>{@code GET /reviews/prompts/mine} — the caller's recent review prompts (customer or worker);</li>
 *   <li>{@code GET /reviews/received} — the reviews the calling worker received, with replies;</li>
 *   <li>{@code POST /reviews/{id}/reply} — the calling worker's public reply to one of them;</li>
 *   <li>{@code GET /public/workers/{workerId}/reviews} — anyone: a worker's verified reviews.</li>
 * </ul>
 * The caller is always the JWT subject, so nobody reads another person's prompts or answers
 * another worker's reviews; roles are enforced by {@code RatingRbacConfig}.
 */
@RestController
public class ReviewExperienceController {

    private final ReviewExperienceService service;

    public ReviewExperienceController(ReviewExperienceService service) {
        this.service = service;
    }

    @GetMapping("/reviews/prompts/mine")
    public List<PromptView> myPrompts() {
        return service.promptsFor(currentUser());
    }

    @GetMapping("/reviews/received")
    public List<ReviewView> received() {
        return service.receivedBy(currentUser());
    }

    @PostMapping("/reviews/{reviewId}/reply")
    public ReviewView reply(@PathVariable("reviewId") UUID reviewId, @RequestBody ReplyRequest request) {
        return service.reply(reviewId, currentUser(), request == null ? null : request.text());
    }

    @GetMapping("/public/workers/{workerId}/reviews")
    public PublicReviews publicReviews(@PathVariable("workerId") UUID workerId,
                                       @RequestParam(name = "page", defaultValue = "0") int page,
                                       @RequestParam(name = "size", defaultValue = "10") int size) {
        return service.publicReviews(workerId, page, size);
    }

    /** The reply text, 1-500 characters. */
    public record ReplyRequest(String text) {
    }

    private static UUID currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ReviewException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "no authenticated principal");
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            throw new ReviewException(HttpStatus.UNAUTHORIZED, "INVALID_PRINCIPAL",
                    "authenticated principal is not a valid user ID");
        }
    }
}
