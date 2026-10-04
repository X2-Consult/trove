import {Component, inject, OnInit} from '@angular/core';
import {FormsModule} from "@angular/forms";
import {ToggleSwitch} from "primeng/toggleswitch";
import {AppSettingKey, AppSettings, PublicReviewSettings, ReviewProviderConfig} from '../../../../shared/model/app-settings.model';
import {AppSettingsService} from '../../../../shared/service/app-settings.service';
import {SettingsHelperService} from '../../../../shared/service/settings-helper.service';
import {Observable} from 'rxjs';
import {filter, take} from 'rxjs/operators';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {TaskService, TaskType} from '../../task-management/task.service';

/** Every night at 3 AM, when the schedule is first turned on here; it can be changed under Tasks. */
const DEFAULT_REVIEW_SCHEDULE = '0 0 3 * * *';

const DEFAULT_PROVIDERS: readonly ReviewProviderConfig[] = [
  {provider: 'Amazon', enabled: true, maxReviews: 5},
  {provider: 'GoodReads', enabled: false, maxReviews: 5},
  {provider: 'Douban', enabled: false, maxReviews: 5}
] as const;

const REQUIRED_PROVIDERS = ['Amazon', 'GoodReads', 'Douban'] as const;

@Component({
  selector: 'app-public-reviews-settings-component',
  imports: [FormsModule, ToggleSwitch, TranslocoDirective],
  templateUrl: './public-reviews-settings-component.html',
  styleUrl: './public-reviews-settings-component.scss'
})
export class PublicReviewsSettingsComponent implements OnInit {

  publicReviewSettings: PublicReviewSettings = {
    downloadEnabled: true,
    autoDownloadEnabled: false,
    providers: [...DEFAULT_PROVIDERS]
  };

  scheduledFetchEnabled = false;
  scheduleCron: string | null = null;
  scheduleUpdating = false;
  readonly DEFAULT_REVIEW_SCHEDULE = DEFAULT_REVIEW_SCHEDULE;

  private readonly appSettingsService = inject(AppSettingsService);
  private readonly taskService = inject(TaskService);
  private readonly settingsHelper = inject(SettingsHelperService);
  private t = inject(TranslocoService);

  readonly appSettings$: Observable<AppSettings | null> = this.appSettingsService.appSettings$;

  ngOnInit(): void {
    this.loadSettings();
    this.loadSchedule();
  }

  onPublicReviewsToggle(checked: boolean): void {
    this.publicReviewSettings.downloadEnabled = checked;
    this.settingsHelper.saveSetting(AppSettingKey.METADATA_PUBLIC_REVIEWS_SETTINGS, this.publicReviewSettings);
  }

  onAutoDownloadToggle(checked: boolean): void {
    this.publicReviewSettings.autoDownloadEnabled = checked;
    this.settingsHelper.saveSetting(AppSettingKey.METADATA_PUBLIC_REVIEWS_SETTINGS, this.publicReviewSettings);
  }

  onScheduledFetchToggle(checked: boolean): void {
    this.scheduleUpdating = true;
    this.taskService.updateCronConfig(TaskType.FETCH_MISSING_REVIEWS, {
      enabled: checked,
      cronExpression: this.scheduleCron || DEFAULT_REVIEW_SCHEDULE
    }).subscribe({
      next: (config) => {
        this.scheduledFetchEnabled = config.enabled;
        this.scheduleCron = config.cronExpression;
        this.scheduleUpdating = false;
        this.settingsHelper.showMessage('success', this.t.translate('common.success'), this.t.translate('settingsMeta.publicReviews.scheduleSaved'));
      },
      error: (error) => {
        console.error('Failed to update review schedule:', error);
        this.scheduledFetchEnabled = !checked;
        this.scheduleUpdating = false;
        this.settingsHelper.showMessage('error', this.t.translate('common.error'), this.t.translate('settingsMeta.publicReviews.scheduleError'));
      }
    });
  }

  private loadSchedule(): void {
    this.taskService.getAvailableTasks().subscribe({
      next: (tasks) => {
        const config = tasks.find(task => task.taskType === TaskType.FETCH_MISSING_REVIEWS)?.cronConfig;
        this.scheduledFetchEnabled = !!config?.enabled;
        this.scheduleCron = config?.cronExpression ?? null;
      },
      error: (error) => console.error('Failed to load review schedule:', error)
    });
  }

  onProviderToggle(providerName: string, enabled: boolean): void {
    this.updateProviderProperty(providerName, 'enabled', enabled);
  }

  onMaxReviewsChange(providerName: string, maxReviews: number): void {
    this.updateProviderProperty(providerName, 'maxReviews', maxReviews);
  }

  private loadSettings(): void {
    this.appSettings$.pipe(
      filter((settings): settings is AppSettings => !!settings),
      take(1)
    ).subscribe({
      next: (settings) => this.initializeSettings(settings),
      error: (error) => {
        console.error('Failed to load settings:', error);
        this.settingsHelper.showMessage('error', this.t.translate('common.error'), this.t.translate('settingsMeta.publicReviews.loadError'));
      }
    });
  }

  private initializeSettings(settings: AppSettings): void {
    if (settings.metadataPublicReviewsSettings) {
      this.publicReviewSettings = {...settings.metadataPublicReviewsSettings};
    }

    this.ensureAllProviders();
  }

  private updateProviderProperty<T extends keyof ReviewProviderConfig>(
    providerName: string,
    property: T,
    value: ReviewProviderConfig[T]
  ): void {
    const provider = this.findProvider(providerName);
    if (provider) {
      provider[property] = value;
      this.settingsHelper.saveSetting(AppSettingKey.METADATA_PUBLIC_REVIEWS_SETTINGS, this.publicReviewSettings);
    }
  }

  private findProvider(providerName: string): ReviewProviderConfig | undefined {
    return this.publicReviewSettings.providers.find(p => p.provider === providerName);
  }

  private ensureAllProviders(): void {
    REQUIRED_PROVIDERS.forEach(providerName => {
      const exists = this.findProvider(providerName);
      if (!exists) {
        this.publicReviewSettings.providers.push({
          provider: providerName,
          enabled: false,
          maxReviews: 10
        });
      }
    });
  }
}
