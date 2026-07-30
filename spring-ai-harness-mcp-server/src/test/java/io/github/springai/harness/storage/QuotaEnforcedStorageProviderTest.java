package io.github.springai.harness.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link QuotaEnforcedStorageProvider}.
 */
@DisplayName("QuotaEnforcedStorageProvider Unit Tests")
@ExtendWith(MockitoExtension.class)
class QuotaEnforcedStorageProviderTest {

	@Mock
	private StorageProvider delegate;

	@Mock
	private QuotaManager quotaManager;

	private QuotaEnforcedStorageProvider provider;

	@BeforeEach
	void setUp() {
		lenient().when(quotaManager.getMetaFile()).thenReturn(".storage");
		lenient().when(quotaManager.getLimitFile()).thenReturn(".quota");
		lenient().when(quotaManager.isSnapshotsIncluded()).thenReturn(false);
		provider = new QuotaEnforcedStorageProvider(delegate, quotaManager);
	}

	@Test
	@DisplayName("Should skip quota check when writing to excluded path")
	void shouldSkipQuotaCheckForExcludedPath() throws IOException {
		provider.writeString(".storage", "some-metadata");

		verify(delegate).writeString(".storage", "some-metadata");
		verify(quotaManager, never()).checkQuota(any(), anyLong());
		verify(quotaManager, never()).updateUsedBytes(any(), anyLong());
	}

	@Test
	@DisplayName("Should skip quota check when writing to .quota limit file")
	void shouldSkipQuotaCheckForLimitFile() throws IOException {
		provider.writeString(".quota", "limitBytes=500\n");

		verify(delegate).writeString(".quota", "limitBytes=500\n");
		verify(quotaManager, never()).checkQuota(any(), anyLong());
		verify(quotaManager, never()).updateUsedBytes(any(), anyLong());
	}

	@Test
	@DisplayName("Should delegate createDirectory without quota checks")
	void shouldDelegateCreateDirectory() throws IOException {
		provider.createDirectory("new-dir");

		verify(delegate).createDirectory("new-dir");
		verify(quotaManager, never()).checkQuota(any(), anyLong());
		verify(quotaManager, never()).updateUsedBytes(any(), anyLong());
	}

	@Test
	@DisplayName("Should check quota and update bytes when writing new file")
	void shouldCheckQuotaForNewFile() throws IOException {
		when(delegate.exists("new.txt")).thenReturn(false);

		provider.writeString("new.txt", "hello"); // 5 bytes

		verify(quotaManager).checkQuota(delegate, 5L);
		verify(delegate).writeString("new.txt", "hello");
		verify(quotaManager).updateUsedBytes(delegate, 5L);
	}

	@Test
	@DisplayName("Should check quota for positive delta when overwriting file")
	void shouldCheckQuotaForPositiveDelta() throws IOException {
		when(delegate.exists("existing.txt")).thenReturn(true);
		when(delegate.isDirectory("existing.txt")).thenReturn(false);
		StorageProvider.Info info = new StorageProvider.Info("existing.txt", true, false, 3L, 1000L);
		when(delegate.getInfo("existing.txt")).thenReturn(info);

		provider.writeString("existing.txt", "hello"); // 5 bytes, delta = +2

		verify(quotaManager).checkQuota(delegate, 2L);
		verify(delegate).writeString("existing.txt", "hello");
		verify(quotaManager).updateUsedBytes(delegate, 2L);
	}

	@Test
	@DisplayName("Should skip quota check but update bytes for negative delta")
	void shouldSkipQuotaCheckForNegativeDelta() throws IOException {
		when(delegate.exists("existing.txt")).thenReturn(true);
		when(delegate.isDirectory("existing.txt")).thenReturn(false);
		StorageProvider.Info info = new StorageProvider.Info("existing.txt", true, false, 8L, 1000L);
		when(delegate.getInfo("existing.txt")).thenReturn(info);

		provider.writeString("existing.txt", "hello"); // 5 bytes, delta = -3

		verify(quotaManager, never()).checkQuota(any(), anyLong());
		verify(delegate).writeString("existing.txt", "hello");
		verify(quotaManager).updateUsedBytes(delegate, -3L);
	}

	@Test
	@DisplayName("Should delete and update bytes based on file size")
	void shouldDeleteAndUpdateBytes() throws IOException {
		when(delegate.exists("file.txt")).thenReturn(true);
		when(delegate.isDirectory("file.txt")).thenReturn(false);
		StorageProvider.Info info = new StorageProvider.Info("file.txt", true, false, 150L, 1000L);
		when(delegate.getInfo("file.txt")).thenReturn(info);

		provider.delete("file.txt");

		verify(delegate).delete("file.txt");
		verify(quotaManager).updateUsedBytes(delegate, -150L);
	}

	@Test
	@DisplayName("Should delegate delete but skip capacity updates for excluded path")
	void shouldSkipDeleteUpdateForExcludedPath() throws IOException {
		// Mock meta file is excluded
		provider.delete(".storage");

		verify(delegate).delete(".storage");
		verify(quotaManager, never()).updateUsedBytes(any(), anyLong());
	}

	@Test
	@DisplayName("Should delegate trash and free capacity when trash is excluded")
	void shouldFreeCapacityOnTrashWhenTrashExcluded() throws IOException {
		when(quotaManager.isTrashIncluded()).thenReturn(false);
		when(delegate.exists("file.txt")).thenReturn(true);
		when(delegate.isDirectory("file.txt")).thenReturn(false);
		StorageProvider.Info info = new StorageProvider.Info("file.txt", true, false, 200L, 1000L);
		when(delegate.getInfo("file.txt")).thenReturn(info);

		provider.trash("file.txt");

		verify(delegate).trash("file.txt");
		verify(quotaManager).updateUsedBytes(delegate, -200L);
	}

	@Test
	@DisplayName("Should delegate trash but not free capacity when trash is included")
	void shouldNotFreeCapacityOnTrashWhenTrashIncluded() throws IOException {
		when(quotaManager.isTrashIncluded()).thenReturn(true);

		provider.trash("file.txt");

		verify(delegate).trash("file.txt");
		verify(quotaManager, never()).updateUsedBytes(any(), anyLong());
	}

	@Test
	@DisplayName("Should check capacity and add bytes when renaming from excluded to normal path")
	void shouldCheckCapacityOnRenameFromExcluded() throws IOException {
		// Rename .snapshots/a.txt to normal.txt
		when(delegate.exists(".snapshots/a.txt")).thenReturn(true);
		when(delegate.isDirectory(".snapshots/a.txt")).thenReturn(false);
		StorageProvider.Info info = new StorageProvider.Info(".snapshots/a.txt", true, false, 300L, 1000L);
		when(delegate.getInfo(".snapshots/a.txt")).thenReturn(info);

		provider.rename(".snapshots/a.txt", "normal.txt");

		verify(quotaManager).checkQuota(delegate, 300L);
		verify(delegate).rename(".snapshots/a.txt", "normal.txt");
		verify(quotaManager).updateUsedBytes(delegate, 300L);
	}

	@Test
	@DisplayName("Should reduce capacity when renaming from normal to excluded path")
	void shouldReduceCapacityOnRenameToExcluded() throws IOException {
		// Rename normal.txt to .snapshots/a.txt
		when(delegate.exists("normal.txt")).thenReturn(true);
		when(delegate.isDirectory("normal.txt")).thenReturn(false);
		StorageProvider.Info info = new StorageProvider.Info("normal.txt", true, false, 300L, 1000L);
		when(delegate.getInfo("normal.txt")).thenReturn(info);

		provider.rename("normal.txt", ".snapshots/a.txt");

		verify(delegate).rename("normal.txt", ".snapshots/a.txt");
		verify(quotaManager).updateUsedBytes(delegate, -300L);
	}

	@Test
	@DisplayName("Should do nothing for capacity when renaming between normal paths")
	void shouldDoNothingOnRenameBetweenNormal() throws IOException {
		provider.rename("a.txt", "b.txt");

		verify(delegate).rename("a.txt", "b.txt");
		verify(quotaManager, never()).checkQuota(any(), anyLong());
		verify(quotaManager, never()).updateUsedBytes(any(), anyLong());
	}

	@Test
	@DisplayName("Should get separator from delegate")
	void shouldGetSeparator() {
		when(delegate.getSeparator()).thenReturn('/');
		assertThat(provider.getSeparator()).isEqualTo('/');
		verify(delegate).getSeparator();
	}

	@Test
	@DisplayName("Should check isIgnoredPath from delegate")
	void shouldCheckIsIgnoredPath() {
		when(delegate.isIgnoredPath("path")).thenReturn(true);
		assertThat(provider.isIgnoredPath("path")).isTrue();
		verify(delegate).isIgnoredPath("path");
	}

	@Test
	@DisplayName("Should create subDirProvider wrapped in QuotaEnforcedStorageProvider")
	void shouldCreateSubDirProvider() {
		StorageProvider subDelegate = mock(StorageProvider.class);
		when(delegate.subDirProvider("sub")).thenReturn(subDelegate);

		StorageProvider subProvider = provider.subDirProvider("sub");

		assertThat(subProvider).isInstanceOf(QuotaEnforcedStorageProvider.class);
		verify(delegate).subDirProvider("sub");
	}

	@Test
	@DisplayName("Should check exists from delegate")
	void shouldCheckExists() {
		when(delegate.exists("path")).thenReturn(true);
		assertThat(provider.exists("path")).isTrue();
		verify(delegate).exists("path");
	}

	@Test
	@DisplayName("Should check isDirectory from delegate")
	void shouldCheckIsDirectory() {
		when(delegate.isDirectory("path")).thenReturn(true);
		assertThat(provider.isDirectory("path")).isTrue();
		verify(delegate).isDirectory("path");
	}

	@Test
	@DisplayName("Should list directory from delegate")
	void shouldListDirectory() throws IOException {
		List<StorageProvider.Info> expected = List.of(new StorageProvider.Info("file", true, false, 10L, 1000L));
		when(delegate.listDirectory("path")).thenReturn(expected);

		List<StorageProvider.Info> actual = provider.listDirectory("path");

		assertThat(actual).isEqualTo(expected);
		verify(delegate).listDirectory("path");
	}

	@Test
	@DisplayName("Should read string from delegate")
	void shouldReadString() throws IOException {
		when(delegate.readString("path")).thenReturn("content");
		assertThat(provider.readString("path")).isEqualTo("content");
		verify(delegate).readString("path");
	}

	@Test
	@DisplayName("Should read all lines from delegate")
	void shouldReadAllLines() throws IOException {
		List<String> expected = List.of("line1", "line2");
		when(delegate.readAllLines("path")).thenReturn(expected);

		List<String> actual = provider.readAllLines("path");

		assertThat(actual).isEqualTo(expected);
		verify(delegate).readAllLines("path");
	}

	@Test
	@DisplayName("Should glob from delegate")
	void shouldGlob() throws IOException {
		List<String> expected = List.of("match1", "match2");
		when(delegate.glob("pattern", "path")).thenReturn(expected);

		List<String> actual = provider.glob("pattern", "path");

		assertThat(actual).isEqualTo(expected);
		verify(delegate).glob("pattern", "path");
	}

	@Test
	@DisplayName("Should grep from delegate")
	void shouldGrep() throws IOException {
		List<String> expected = List.of("match1");
		when(delegate.grep("pat", "path", "glb", StorageProvider.GrepOutputMode.content, 1, 1, 1, true, true, 10, 0, true))
				.thenReturn(expected);

		List<String> actual = provider.grep("pat", "path", "glb", StorageProvider.GrepOutputMode.content, 1, 1, 1, true, true, 10, 0, true);

		assertThat(actual).isEqualTo(expected);
		verify(delegate).grep("pat", "path", "glb", StorageProvider.GrepOutputMode.content, 1, 1, 1, true, true, 10, 0, true);
	}

	@Test
	@DisplayName("Should get info of single path from delegate")
	void shouldGetInfoSingle() throws IOException {
		StorageProvider.Info expected = new StorageProvider.Info("path", true, false, 10L, 1000L);
		when(delegate.getInfo("path")).thenReturn(expected);

		StorageProvider.Info actual = provider.getInfo("path");

		assertThat(actual).isEqualTo(expected);
		verify(delegate).getInfo("path");
	}

	@Test
	@DisplayName("Should get info of list of paths from delegate")
	void shouldGetInfoList() {
		List<StorageProvider.Info> expected = List.of(new StorageProvider.Info("path", true, false, 10L, 1000L));
		when(delegate.getInfo(List.of("path"))).thenReturn(expected);

		List<StorageProvider.Info> actual = provider.getInfo(List.of("path"));

		assertThat(actual).isEqualTo(expected);
		verify(delegate).getInfo(List.of("path"));
	}

	@Test
	@DisplayName("Should read image from delegate")
	void shouldReadImage() throws IOException {
		when(delegate.readImage("path")).thenReturn("base64");
		assertThat(provider.readImage("path")).isEqualTo("base64");
		verify(delegate).readImage("path");
	}

	@Test
	@DisplayName("Should read pdf from delegate")
	void shouldReadPdf() throws IOException {
		when(delegate.readPdf("path", 1, 2)).thenReturn("pdfText");
		assertThat(provider.readPdf("path", 1, 2)).isEqualTo("pdfText");
		verify(delegate).readPdf("path", 1, 2);
	}

	@Test
	@DisplayName("Should read document from delegate")
	void shouldReadDocument() throws IOException {
		when(delegate.readDocument("path")).thenReturn("docText");
		assertThat(provider.readDocument("path")).isEqualTo("docText");
		verify(delegate).readDocument("path");
	}

	@Test
	@DisplayName("Should calculateTotalSize from delegate")
	void shouldCalculateTotalSize() throws IOException {
		when(delegate.calculateTotalSize(List.of("exclude"))).thenReturn(500L);
		assertThat(provider.calculateTotalSize(List.of("exclude"))).isEqualTo(500L);
		verify(delegate).calculateTotalSize(List.of("exclude"));
	}

	@Test
	@DisplayName("Should check capacity on write to snapshots when snapshots are included")
	void shouldCheckCapacityOnWriteToSnapshotsWhenSnapshotsIncluded() throws IOException {
		when(quotaManager.isSnapshotsIncluded()).thenReturn(true);
		when(delegate.exists(".snapshots/a.txt")).thenReturn(false);

		provider.writeString(".snapshots/a.txt", "hello");

		verify(quotaManager).checkQuota(delegate, 5L);
		verify(delegate).writeString(".snapshots/a.txt", "hello");
		verify(quotaManager).updateUsedBytes(delegate, 5L);
	}

	@Test
	@DisplayName("Should delegate createDownloadLink directly without quota checks")
	void shouldDelegateCreateDownloadLink() throws Exception {
		String path = "test.txt";
		Duration ttl = Duration.ofMinutes(5);
		DownloadLink expected = new DownloadLink(new URI("http://mock"), Date.from(Instant.now().plus(ttl)), "test.txt", 100L);
		when(delegate.createDownloadLink(path, ttl)).thenReturn(expected);

		DownloadLink actual = provider.createDownloadLink(path, ttl);

		assertThat(actual).isEqualTo(expected);
		verify(delegate).createDownloadLink(path, ttl);
		verify(quotaManager, never()).checkQuota(any(), anyLong());
		verify(quotaManager, never()).updateUsedBytes(any(), anyLong());
	}

	@Test
	@DisplayName("Should check capacity on writeFile and delegate write")
	void shouldCheckCapacityOnWriteFile() throws IOException {
		InputStream is = mock(InputStream.class);
		when(delegate.exists("file.txt")).thenReturn(false);

		provider.writeFile("file.txt", is, 100L);

		verify(quotaManager).checkQuota(delegate, 100L);
		verify(delegate).writeFile("file.txt", is, 100L);
		verify(quotaManager).updateUsedBytes(delegate, 100L);
	}

	@Test
	@DisplayName("Should not check capacity on writeFile to excluded paths")
	void shouldNotCheckCapacityOnWriteFileToExcluded() throws IOException {
		InputStream is = mock(InputStream.class);

		provider.writeFile(".snapshots/file.txt", is, 100L);

		verify(delegate).writeFile(".snapshots/file.txt", is, 100L);
		verify(quotaManager, never()).checkQuota(any(), anyLong());
		verify(quotaManager, never()).updateUsedBytes(any(), anyLong());
	}
}
