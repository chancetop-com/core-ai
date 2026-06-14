/* ========================================
   Chancetop 2026 Hackathon — Main Script
   ======================================== */

document.addEventListener('DOMContentLoaded', () => {
  // =========================================
  // 1. Scroll-Triggered Reveal (Intersection Observer)
  // =========================================
  const revealElements = document.querySelectorAll('.reveal');

  const revealObserver = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add('visible');
          // Optionally unobserve after reveal for performance
          // revealObserver.unobserve(entry.target);
        }
      });
    },
    {
      threshold: 0.15,
      rootMargin: '0px 0px -40px 0px',
    }
  );

  revealElements.forEach((el) => revealObserver.observe(el));

  // =========================================
  // 2. Navigation — Active Section Highlight
  // =========================================
  const navLinks = document.querySelectorAll('.nav-link');
  const sections = document.querySelectorAll('section[id]');

  const sectionObserver = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          const id = entry.target.getAttribute('id');
          navLinks.forEach((link) => {
            link.classList.toggle(
              'active',
              link.getAttribute('href') === `#${id}`
            );
          });
        }
      });
    },
    {
      threshold: 0.3,
      rootMargin: '-64px 0px 0px 0px', // offset by navbar height
    }
  );

  sections.forEach((section) => sectionObserver.observe(section));

  // =========================================
  // 3. Mobile Hamburger Menu
  // =========================================
  const hamburger = document.getElementById('hamburger');
  const navLinksContainer = document.getElementById('navLinks');

  if (hamburger && navLinksContainer) {
    hamburger.addEventListener('click', () => {
      hamburger.classList.toggle('active');
      navLinksContainer.classList.toggle('open');
    });

    // Close mobile menu on link click
    navLinksContainer.querySelectorAll('.nav-link').forEach((link) => {
      link.addEventListener('click', () => {
        hamburger.classList.remove('active');
        navLinksContainer.classList.remove('open');
      });
    });

    // Close mobile menu on outside click (optional, handled by link clicks)
    document.addEventListener('click', (e) => {
      if (
        navLinksContainer.classList.contains('open') &&
        !hamburger.contains(e.target) &&
        !navLinksContainer.contains(e.target)
      ) {
        hamburger.classList.remove('active');
        navLinksContainer.classList.remove('open');
      }
    });
  }

  // =========================================
  // 4. Form Iframe — Loading State
  // =========================================
  const formIframe = document.getElementById('formIframe');
  const formLoading = document.getElementById('formLoading');
  const formFallback = document.getElementById('formFallback');
  const fallbackLink = document.getElementById('fallbackLink');

  /**
   * Form URL configuration.
   * Update this once AE confirms the platform.
   * Default: Google Form placeholder.
   */
  const FORM_URL =
    'https://docs.google.com/forms/d/e/FAKE_FORM_ID/viewform?embedded=true';
  const FORM_FALLBACK_URL =
    'https://docs.google.com/forms/d/e/FAKE_FORM_ID/viewform';

  if (formIframe) {
    // Set the iframe src
    formIframe.src = FORM_URL;

    // When iframe loads, hide the loading indicator
    formIframe.addEventListener('load', () => {
      if (formLoading) {
        formLoading.classList.add('hidden');
      }
    });

    // Timeout fallback: if iframe doesn't load within 10 seconds,
    // show the fallback link
    let formLoadTimeout = setTimeout(() => {
      if (
        formLoading &&
        !formLoading.classList.contains('hidden')
      ) {
        formLoading.classList.add('hidden');
        if (formFallback) {
          formFallback.style.display = 'block';
        }
      }
    }, 10000);

    // Clear timeout if iframe loads successfully
    formIframe.addEventListener('load', () => {
      clearTimeout(formLoadTimeout);
    });

    // Set fallback URL
    if (fallbackLink) {
      fallbackLink.href = FORM_FALLBACK_URL;
    }
  }

  // =========================================
  // 5. Navbar Shadow on Scroll
  // =========================================
  const navbar = document.getElementById('navbar');

  if (navbar) {
    let lastScrollY = 0;

    window.addEventListener('scroll', () => {
      const scrollY = window.scrollY;
      if (scrollY > 10) {
        navbar.style.borderBottomColor = 'rgba(30, 41, 59, 0.8)';
      } else {
        navbar.style.borderBottomColor = 'var(--color-border)';
      }
      lastScrollY = scrollY;
    });
  }

  // =========================================
  // 6. Console greeting (easter egg)
  // =========================================
  console.log(
    '%c Chancetop 2026 Hackathon %c Built with ❤️ by the AITHON team ',
    'background:#7c3aed;color:#fff;padding:4px 8px;border-radius:4px 0 0 4px;font-weight:bold',
    'background:#141428;color:#00d4ff;padding:4px 8px;border-radius:0 4px 4px 0'
  );
});
