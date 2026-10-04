import {HttpInterceptorFn} from '@angular/common/http';

const IOS_PLATFORMS = ['iPad Simulator', 'iPhone Simulator', 'iPod Simulator', 'iPad', 'iPhone', 'iPod'];

function isIOSWebkit(): boolean {
  return IOS_PLATFORMS.includes(navigator.platform)
    // iPadOS 13+ reports itself as a Mac
    || (navigator.userAgent.includes('Mac') && 'ontouchend' in document);
}

/**
 * Works around a WebKit bug that breaks file uploads on iPhone and iPad when the request goes through
 * the service worker (https://bugs.webkit.org/show_bug.cgi?id=319985): on iOS, every request with a
 * FormData body gets its files copied with File.slice and skips the service worker. Covers every
 * upload, including PrimeNG's file-upload buttons, in one place. From Grimmory b89449ca.
 */
export const webkitUploadInterceptor: HttpInterceptorFn = (req, next) => {
  if (!(req.body instanceof FormData) || !isIOSWebkit()) {
    return next(req);
  }
  const body = new FormData();
  for (const key of new Set(req.body.keys())) {
    for (const value of req.body.getAll(key)) {
      if (typeof value === 'string') {
        body.append(key, value);
      } else {
        body.append(key, value.slice(0, value.size, value.type), value.name);
      }
    }
  }
  return next(req.clone({headers: req.headers.set('ngsw-bypass', '1'), body}));
};
