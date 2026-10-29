package com.placementtracker.web;

import java.io.IOException;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Guards the local API against DNS rebinding and cross-site requests: the Host header must be this machine on the
 * app's port, and anything that changes data must carry the custom header a plain browser form cannot send.
 */
@Component
public class LocalOnlyFilter extends OncePerRequestFilter {

	static final Set<String> HOSTS = Set.of("127.0.0.1:8765", "localhost:8765");
	static final Set<String> MUTATING = Set.of("POST", "PATCH", "PUT", "DELETE");

	@Override
	protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
			throws ServletException, IOException {
		String host = req.getHeader("Host");
		if (host == null || !HOSTS.contains(host)) {
			reject(res, "Host not allowed");
			return;
		}
		if (MUTATING.contains(req.getMethod().toUpperCase()) && !"1".equals(req.getHeader("X-Tracker"))) {
			reject(res, "Missing X-Tracker header");
			return;
		}
		chain.doFilter(req, res);
	}

	private static void reject(HttpServletResponse res, String message) throws IOException {
		res.setStatus(HttpServletResponse.SC_FORBIDDEN);
		res.setContentType("application/json");
		res.getWriter().write("{\"error\":\"" + message + "\"}");
	}
}
