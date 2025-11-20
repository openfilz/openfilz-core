import { Injectable } from '@angular/core';
import { of } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class MockAuthService {
    userData$ = of({ sub: 'mock-user', name: 'Mock User', preferred_username: 'mockuser' });
    isAuthenticated$ = of({ isAuthenticated: true });

    checkAuth() {
        return of({ isAuthenticated: true, userData: { sub: 'mock-user' }, accessToken: 'mock-token' });
    }

    logoff() {
        console.log('Mock logoff');
        return of(null);
    }

    authorize() {
        console.log('Mock authorize');
    }

    getAccessToken() {
        return 'mock-token';
    }
}
