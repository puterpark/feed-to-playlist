package us.puter.feedtoplaylist.web;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import us.puter.feedtoplaylist.global.properties.SiteProperties;

/**
 * Google OAuth 검수에 필요한 공개 페이지: 홈, 개인정보처리방침, 서비스 약관.
 */
@Controller
@RequiredArgsConstructor
public class PageController {

    private final SiteProperties site;

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("site", site);
        return "index";
    }

    @GetMapping("/privacy")
    public String privacy(Model model) {
        model.addAttribute("site", site);
        return "privacy";
    }

    @GetMapping("/terms")
    public String terms(Model model) {
        model.addAttribute("site", site);
        return "terms";
    }
}
