package com.example.monitoring.service;

import com.google.common.hash.HashCode;
import org.springframework.stereotype.Service;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;

@Service
public class StudentUserServiceImpl implements StudentUserService {
    @Override
    public boolean userAuthorized(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        Optional<Cookie> userId = Arrays.stream(cookies).filter(cookie -> cookie.getName().equals("userId")).findAny();
        return userId.isPresent();
    }

    @Override
    public Cookie saveUserCookie() {
        HashCode hashCode = HashCode.fromLong(LocalDateTime.now().toEpochSecond(ZoneOffset.MIN));
        return new Cookie("userId", hashCode.toString());
    }
}
