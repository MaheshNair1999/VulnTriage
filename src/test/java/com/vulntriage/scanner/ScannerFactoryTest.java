package com.vulntriage.scanner;

import com.vulntriage.domain.enums.ScannerType;
import com.vulntriage.scanner.factory.ScannerFactory;
import com.vulntriage.scanner.semgrep.SemgrepAdapter;
import com.vulntriage.scanner.trivy.TrivyAdapter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScannerFactoryTest {

    @Test
    void create_semgrep_returnsSemgrepAdapter() {
        var adapter = ScannerFactory.create(ScannerType.SEMGREP);
        assertInstanceOf(SemgrepAdapter.class, adapter);
        assertEquals(ScannerType.SEMGREP, adapter.getScannerType());
    }

    @Test
    void create_trivy_returnsTrivyAdapter() {
        var adapter = ScannerFactory.create(ScannerType.TRIVY);
        assertInstanceOf(TrivyAdapter.class, adapter);
        assertEquals(ScannerType.TRIVY, adapter.getScannerType());
    }
}
