import { inject } from '@angular/core';
import { Router, CanActivateFn } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { map, take } from 'rxjs/operators';
import { of } from 'rxjs';
import { environment } from '../../environments/environment';

export const authGuard: CanActivateFn = (route, state) => {
    if (!environment.authentication.enabled) {
        return of(true);
    }

    const oidcSecurityService = inject(OidcSecurityService);
    const router = inject(Router);

    return oidcSecurityService.isAuthenticated$.pipe(
        take(1),
        map(({ isAuthenticated }) => {
            if (isAuthenticated) {
                return true;
            }

            oidcSecurityService.authorize();
            return false;
        })
    );
};
